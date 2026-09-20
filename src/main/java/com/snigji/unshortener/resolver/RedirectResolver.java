package com.snigji.unshortener.resolver;

import com.snigji.unshortener.cache.EdgeCache;
import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.Hop;
import com.snigji.unshortener.domain.HopVia;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import com.snigji.unshortener.domain.StopReason;
import com.snigji.unshortener.security.Guard;
import com.snigji.unshortener.ua.UaProfile;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpClosedException;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks a redirect chain one hop at a time, recursively, honoring one absolute
 * deadline for the whole walk. Never throws on a resolution failure — always
 * completes with a {@link Result} carrying success/failure plus the partial chain.
 */
@ApplicationScoped
public class RedirectResolver {

    private static final Logger LOG = Logger.getLogger(RedirectResolver.class);
    private static final Set<Integer> GET_FALLBACK_STATUSES = Set.of(405, 501, 400, 403);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    @Inject
    WebClient webClient;

    @Inject
    Guard guard;

    @Inject
    EdgeCache edgeCache;

    public Uni<Result> resolve(URI url, UaProfile profile) {
        WalkState state = new WalkState(url.toString(), profile);
        long startNanos = System.nanoTime();
        LOG.debugf("resolve start: url=%s profile=%s", url, profile.name());
        return step(url, state, HopVia.INITIAL)
                .map(outcome -> buildResult(url, state, outcome, startNanos));
    }

    private Uni<WalkOutcome> step(URI current, WalkState state, HopVia via) {
        if (!state.markVisited(current)) {
            LOG.debugf("stop LOOP at %s (hop %d, via=%s)", current, state.hopCount(), via);
            return Uni.createFrom().item(new WalkOutcome(new Destination.Web(current), StopReason.LOOP));
        }
        if (state.atMaxHops()) {
            LOG.debugf("stop MAX_HOPS at %s (%d hops reached)", current, state.hopCount());
            return Uni.createFrom().item(new WalkOutcome(new Destination.Web(current), StopReason.MAX_HOPS));
        }
        if (state.belowFloor()) {
            LOG.debugf("stop DEADLINE at %s (%dms remaining, below floor)", current, state.remainingMs());
            return Uni.createFrom().item(new WalkOutcome(new Destination.Web(current), StopReason.DEADLINE));
        }

        long hopTimeoutMs = state.hopTimeoutMs();
        LOG.debugf("hop %d: %s via=%s timeoutMs=%d remainingMs=%d",
                state.hopCount() + 1, current, via, hopTimeoutMs, state.remainingMs());
        return computeHop(current, state, via, hopTimeoutMs)
                .flatMap(computation -> {
                    state.addHop(computation.hop());
                    edgeCache.put(current, state.profile().name(), computation);
                    return switch (computation.outcome()) {
                        case HopOutcome.Redirect r -> {
                            LOG.debugf("hop %d redirect: %s -> %s (status=%d, via=%s)",
                                    state.hopCount(), current, r.next(), computation.hop().statusCode(), r.via());
                            yield step(r.next(), state, r.via());
                        }
                        case HopOutcome.Terminal t -> {
                            LOG.debugf("hop %d terminal: %s reason=%s destination=%s",
                                    state.hopCount(), current, t.reason(), t.destination());
                            yield Uni.createFrom().item(new WalkOutcome(t.destination(), t.reason()));
                        }
                    };
                });
    }

    private Uni<HopComputation> computeHop(URI target, WalkState state, HopVia via, long timeoutMs) {
        return edgeCache.get(target, state.profile().name())
                // `via` describes how *this* walk reached the target, not a property of the
                // target itself — a cache hit from a walk that arrived here differently (say,
                // by a plain redirect instead of a meta-refresh) must not carry the old via.
                .map(cached -> {
                    LOG.debugf("edge cache hit for %s (profile=%s)", target, state.profile().name());
                    return Uni.createFrom().item(withVia(cached, via));
                })
                .orElseGet(() -> {
                    LOG.debugf("edge cache miss for %s (profile=%s), fetching", target, state.profile().name());
                    return fetchHop(target, state, via, timeoutMs);
                });
    }

    private HopComputation withVia(HopComputation computation, HopVia via) {
        Hop original = computation.hop();
        if (original.via() == via) {
            return computation;
        }
        Hop updated = new Hop(original.url(), original.method(), original.statusCode(), original.location(),
                via, original.remoteIp(), original.contentType(), original.elapsedMs());
        return new HopComputation(updated, computation.outcome(), computation.cacheable());
    }

    private Uni<HopComputation> fetchHop(URI target, WalkState state, HopVia via, long timeoutMs) {
        guard.checkUrl(target);
        long hopStartNanos = System.nanoTime();
        LOG.debugf("HEAD %s (timeoutMs=%d)", target, timeoutMs);
        return sendRequest(target, "HEAD", state, timeoutMs)
                .flatMap(headResponse -> {
                    boolean fallbackStatus = GET_FALLBACK_STATUSES.contains(headResponse.statusCode());
                    // HEAD never carries a body per the HTTP spec, so a terminal-looking 200 HTML
                    // response needs an actual GET before we can scan it for meta-refresh/JS intent.
                    boolean needsBodyForHtmlScan = headResponse.statusCode() == 200 && isHtml(headResponse.getHeader("Content-Type"));
                    if (fallbackStatus || needsBodyForHtmlScan) {
                        LOG.debugf("HEAD %s -> %d, falling back to GET (fallbackStatus=%s, htmlScan=%s)",
                                target, headResponse.statusCode(), fallbackStatus, needsBodyForHtmlScan);
                        // The GET reuses the just-freed HEAD connection from the pool; if the
                        // server closed it right as it went back in, the retry gets a fresh one.
                        return sendRequest(target, "GET", state, timeoutMs)
                                .onFailure(RedirectResolver::isStaleConnection).retry().atMost(1)
                                .map(getResponse -> new SimpleEntry<>("GET", getResponse));
                    }
                    return Uni.createFrom().item(new SimpleEntry<>("HEAD", headResponse));
                })
                .map(entry -> interpret(target, entry.getKey(), entry.getValue(), via, state, hopStartNanos))
                .onFailure().invoke(t -> LOG.debugf(t, "hop failed for %s", target))
                .onFailure().recoverWithItem(t -> errorComputation(target, via, hopStartNanos, t));
    }

    private Uni<HttpResponse<Buffer>> sendRequest(URI target, String method, WalkState state, long timeoutMs) {
        HttpRequest<Buffer> request = "HEAD".equals(method)
                ? webClient.headAbs(target.toString())
                : webClient.getAbs(target.toString());
        // Headers rebuilt from scratch on every request object — nothing leaks across a host boundary.
        for (Map.Entry<String, String> header : state.profile().headers()) {
            request.putHeader(header.getKey(), header.getValue());
        }
        if (target.getHost() != null) {
            state.cookieJar().cookieHeaderFor(target.getHost()).ifPresent(c -> request.putHeader("Cookie", c));
        }
        if ("GET".equals(method)) {
            request.putHeader("Range", "bytes=0-" + (ResolverLimits.MAX_BODY_BYTES - 1));
        }
        return request.timeout(timeoutMs).send();
    }

    private HopComputation interpret(URI target, String method, HttpResponse<Buffer> response, HopVia via,
            WalkState state, long hopStartNanos) {
        long elapsedMs = elapsedMs(hopStartNanos);
        int status = response.statusCode();
        String contentType = response.getHeader("Content-Type");
        LOG.debugf("%s %s -> %d (%s, %dms)", method, target, status, contentType, elapsedMs);
        List<String> setCookies = response.headers().getAll("Set-Cookie");
        if (!setCookies.isEmpty() && target.getHost() != null) {
            LOG.debugf("storing %d cookie(s) for %s", setCookies.size(), target.getHost());
            state.cookieJar().store(target.getHost(), setCookies);
        }
        // Cookie-bearing hops are not edge-cacheable: replaying from cache would silently
        // skip the Set-Cookie a later hop might depend on.
        boolean cacheable = setCookies.isEmpty();

        if (REDIRECT_STATUSES.contains(status)) {
            String location = response.getHeader("Location");
            Hop hop = new Hop(target.toString(), method, status, location, via, null, contentType, elapsedMs);
            if (location == null || location.isBlank()) {
                return new HopComputation(hop,
                        new HopOutcome.Terminal(new Destination.Web(target), StopReason.TERMINAL_RESPONSE), cacheable);
            }
            return new HopComputation(hop, resolveRedirectTarget(target, location), cacheable);
        }

        if (status == 200 && isHtml(contentType)) {
            String body = capBody(response.body());
            Optional<String> metaRefresh = MetaRefreshScanner.findRefreshUrl(body);
            if (metaRefresh.isPresent()) {
                Hop hop = new Hop(target.toString(), method, status, metaRefresh.get(), via, null, contentType, elapsedMs);
                URI next = resolveLocation(target, metaRefresh.get());
                if (next != null && UrlNormalizer.isHttp(next)) {
                    return new HopComputation(hop,
                            new HopOutcome.Redirect(UrlNormalizer.normalizeHttp(next), HopVia.META_REFRESH), cacheable);
                }
                return new HopComputation(hop,
                        new HopOutcome.Terminal(new Destination.Web(target), StopReason.TERMINAL_RESPONSE), cacheable);
            }
            Optional<String> jsRedirect = JsRedirectDetector.detect(body);
            if (jsRedirect.isPresent()) {
                LOG.infof("js_suspected at %s (extracted=%s)", target, jsRedirect.get());
                Hop hop = new Hop(target.toString(), method, status, null, via, null, contentType, elapsedMs);
                return new HopComputation(hop,
                        new HopOutcome.Terminal(new Destination.Web(target), StopReason.JS_SUSPECTED), cacheable);
            }
        }

        Hop hop = new Hop(target.toString(), method, status, null, via, null, contentType, elapsedMs);
        return new HopComputation(hop, new HopOutcome.Terminal(new Destination.Web(target), StopReason.TERMINAL_RESPONSE), cacheable);
    }

    /** Non-HTTP redirect targets (intent://, market://) are a terminal success, not an error. */
    private HopOutcome resolveRedirectTarget(URI from, String location) {
        URI resolved = resolveLocation(from, location);
        if (resolved == null) {
            return new HopOutcome.Terminal(new Destination.Web(from), StopReason.TERMINAL_RESPONSE);
        }
        String scheme = resolved.getScheme();
        if (IntentUrlParser.isIntent(scheme)) {
            URI fallback = IntentUrlParser.extractFallback(resolved.toString()).orElse(null);
            return new HopOutcome.Terminal(new Destination.AppIntent(resolved, fallback), StopReason.NON_HTTP_SCHEME);
        }
        if (IntentUrlParser.isMarket(scheme)) {
            String packageId = IntentUrlParser.extractPackageId(resolved.toString())
                    .orElse(resolved.getSchemeSpecificPart());
            return new HopOutcome.Terminal(new Destination.Store(packageId), StopReason.NON_HTTP_SCHEME);
        }
        if (!UrlNormalizer.isHttp(resolved)) {
            return new HopOutcome.Terminal(new Destination.Unresolved(StopReason.NON_HTTP_SCHEME), StopReason.NON_HTTP_SCHEME);
        }
        return new HopOutcome.Redirect(UrlNormalizer.normalizeHttp(resolved), HopVia.HTTP);
    }

    /** Resolves a Location/meta-refresh value against the current URL: relative paths, missing schemes, unencoded chars. */
    private URI resolveLocation(URI base, String location) {
        String trimmed = location.trim();
        URI parsed = tryParse(trimmed);
        if (parsed == null) {
            parsed = tryParse(trimmed.replace(" ", "%20"));
        }
        if (parsed == null) {
            return null;
        }
        try {
            return base.resolve(parsed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private URI tryParse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private HopComputation errorComputation(URI target, HopVia via, long hopStartNanos, Throwable t) {
        long elapsedMs = elapsedMs(hopStartNanos);
        StopReason reason = classifyError(t);
        LOG.debugf("classified error for %s as %s: %s", target, reason, t.toString());
        // Method is unknown here (the failure could have happened on the HEAD attempt or its GET
        // fallback) — HEAD is the common case, since transport/DNS failures happen on first contact.
        Hop hop = new Hop(target.toString(), "HEAD", null, null, via, null, null, elapsedMs);
        return new HopComputation(hop, new HopOutcome.Terminal(new Destination.Unresolved(reason), reason), true);
    }

    private StopReason classifyError(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) {
                return StopReason.DNS_ERROR;
            }
        }
        return StopReason.TRANSPORT_ERROR;
    }

    private static boolean isStaleConnection(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpClosedException) {
                return true;
            }
        }
        return false;
    }

    private Result buildResult(URI normalizedInput, WalkState state, WalkOutcome outcome, long startNanos) {
        // The final hop recorded is always the terminal one (success or error) — "any hop
        // completed" means there's at least one *earlier* hop besides that terminal attempt.
        boolean anyHopCompleted = state.hopCount() > 1;
        Status status = Status.from(outcome.reason(), anyHopCompleted);
        String finalUrl = finalUrl(outcome.destination(), state, normalizedInput);
        boolean resumable = status == Status.PARTIAL
                && outcome.destination() instanceof Destination.Web(URI uri)
                && edgeCache.isWarm(uri, state.profile().name());
        long totalElapsedMs = elapsedMs(startNanos);
        LOG.debugf("resolve done: input=%s finalUrl=%s status=%s reason=%s hops=%d elapsedMs=%d resumable=%s",
                normalizedInput, finalUrl, status, outcome.reason(), state.hopCount(), totalElapsedMs, resumable);
        return new Result(state.rawInput(), finalUrl, outcome.destination(), status, outcome.reason(),
                state.hops(), totalElapsedMs, state.remainingMs(), false, resumable);
    }

    private String finalUrl(Destination destination, WalkState state, URI normalizedInput) {
        return switch (destination) {
            case Destination.Web web -> web.uri().toString();
            case Destination.AppIntent appIntent -> appIntent.raw().toString();
            case Destination.Store store -> "market://details?id=" + store.packageId();
            case Destination.Unresolved ignored -> lastKnownUrl(state, normalizedInput);
        };
    }

    private String lastKnownUrl(WalkState state, URI normalizedInput) {
        List<Hop> hops = state.hops();
        return hops.isEmpty() ? normalizedInput.toString() : hops.getLast().url();
    }

    private static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static String capBody(Buffer buffer) {
        if (buffer == null) {
            return "";
        }
        int len = Math.min(buffer.length(), (int) ResolverLimits.MAX_BODY_BYTES);
        return buffer.getBuffer(0, len).toString(StandardCharsets.UTF_8);
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
