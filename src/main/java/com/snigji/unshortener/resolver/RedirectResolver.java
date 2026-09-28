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
import io.vertx.mutiny.core.Vertx;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Walks a redirect chain one hop at a time, recursively, honoring one absolute
 * deadline for the whole walk. Never throws on a resolution failure — always
 * completes with a {@link Result} carrying success/failure plus the partial chain.
 */
@ApplicationScoped
public class RedirectResolver {

    private static final Logger LOG = Logger.getLogger(RedirectResolver.class);
    // Ad-hoc, grown one host-quirk at a time rather than a general rule (e.g. "any non-2xx/
    // 3xx HEAD status triggers a confirmatory GET") — each entry is a status a real host was
    // observed sending for a HEAD it couldn't or wouldn't answer meaningfully: 400/403 (HEAD
    // rejected by a WAF or quirky host that answers GET fine), 404/405 (HEAD reported as
    // missing/not-allowed on a resource that exists), 501 (HEAD not implemented at all).
    private static final Set<Integer> GET_FALLBACK_STATUSES = Set.of(400, 403, 404, 405, 501);
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    @Inject
    WebClient webClient;

    @Inject
    Vertx vertx;

    @Inject
    Guard guard;

    @Inject
    EdgeCache edgeCache;

    public Uni<Result> resolve(URI url, UaProfile profile) {
        AtomicReference<WalkState> stateRef = new AtomicReference<>();
        long startNanos = System.nanoTime();
        // The whole body is wrapped in `deferred` for a reason beyond laziness: nothing
        // downstream of `step()` for hop 1 is itself wrapped in a Uni factory (unlike
        // fetchHop's HEAD/GET legs), so without this, hop 1's entire synchronous call chain
        // — including `guard.checkUrl`, a documented no-op today (AllowAllGuard) but one
        // whose Javadoc describes a real implementation designed to reject/throw — would
        // run eagerly the moment `resolve` is called, before any Uni exists to attach a
        // failure handler to. `deferred` turns a thrown exception there into a Uni failure
        // instead of letting it escape past every `onFailure()` in this class.
        return Uni.createFrom().<Result>deferred(() -> {
            WalkState state = new WalkState(profile);
            stateRef.set(state);
            LOG.debugf("resolve start: url=%s profile=%s", url, profile.name());
            return step(url, state, HopVia.INITIAL)
                    .map(outcome -> buildResult(url, state, outcome, startNanos));
        }).onFailure().invoke(t -> LOG.errorf(t, "unexpected internal failure resolving %s", url))
          .onFailure().recoverWithItem(t -> internalErrorResult(url, stateRef.get()));
    }

    /**
     * Safety net for {@code resolve()}'s own boundary, distinct from {@code fetchHop}'s
     * per-hop recovery below — it exists for exactly the "never rely on fetchHop happening
     * to catch everything" case: a bug in {@code buildResult} itself, or an exception from
     * {@code guard.checkUrl} once a real {@link com.snigji.unshortener.security.Guard} is
     * wired in. Reports whatever hops were already gathered, same as a normal partial —
     * unlike {@link #buildResult}, there's no extra terminal hop to subtract here, since
     * the crash happened before one could be constructed for the failing attempt.
     */
    private Result internalErrorResult(URI url, WalkState state) {
        List<Hop> hops = state == null ? List.of() : state.hops();
        boolean anyHopCompleted = !hops.isEmpty();
        String finalUrl = hops.isEmpty() ? url.toString() : hops.getLast().url();
        long budgetRemainingMs = state == null ? 0 : state.remainingMs();
        return new Result(url.toString(), finalUrl, new Destination.Unresolved(StopReason.INTERNAL_ERROR),
                Status.from(StopReason.INTERNAL_ERROR, anyHopCompleted), StopReason.INTERNAL_ERROR, hops, 0,
                budgetRemainingMs, false, false);
    }

    private Uni<HopOutcome.Terminal> step(URI current, WalkState state, HopVia via) {
        if (!state.markVisited(current)) {
            LOG.debugf("stop LOOP at %s (hop %d, via=%s)", current, state.hopCount(), via);
            return stop(current, StopReason.LOOP);
        }
        if (state.atMaxHops()) {
            LOG.debugf("stop MAX_HOPS at %s (%d hops reached)", current, state.hopCount());
            return stop(current, StopReason.MAX_HOPS);
        }
        if (state.belowFloor()) {
            LOG.debugf("stop DEADLINE at %s (%dms remaining, below floor)", current, state.remainingMs());
            return stop(current, StopReason.DEADLINE);
        }

        long hopTimeoutMs = state.hopTimeoutMs();
        LOG.debugf("hop %d: %s via=%s timeoutMs=%d remainingMs=%d",
                state.hopCount() + 1, current, via, hopTimeoutMs, state.remainingMs());
        return computeHop(current, state, via, hopTimeoutMs)
                .flatMap(computation -> {
                    state.addHop(computation.hop());
                    return switch (computation.outcome()) {
                        case HopOutcome.Redirect r -> {
                            LOG.debugf("hop %d redirect: %s -> %s (status=%d, via=%s)",
                                    state.hopCount(), current, r.next(), computation.hop().statusCode(), r.via());
                            yield step(r.next(), state, r.via());
                        }
                        case HopOutcome.Terminal t -> {
                            LOG.debugf("hop %d terminal: %s reason=%s destination=%s",
                                    state.hopCount(), current, t.reason(), t.destination());
                            yield Uni.createFrom().item(t);
                        }
                    };
                });
    }

    private static Uni<HopOutcome.Terminal> stop(URI current, StopReason reason) {
        return Uni.createFrom().item(new HopOutcome.Terminal(new Destination.Web(current), reason));
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
                    return fetchHop(target, state, via, timeoutMs)
                            .invoke(computation -> edgeCache.put(target, state.profile().name(), computation));
                });
    }

    private HopComputation withVia(HopComputation computation, HopVia via) {
        Hop original = computation.hop();
        if (original.via() == via) {
            return computation;
        }
        return new HopComputation(original.withVia(via), computation.outcome(), computation.cacheable());
    }

    /** Tags a response with which HTTP method actually produced it — HEAD, or a GET fallback. */
    private record MethodResponse(String method, HttpResponse<Buffer> response) {}

    private static Uni<HttpResponse<Buffer>> withStaleConnectionRetry(Uni<HttpResponse<Buffer>> uni) {
        return uni.onFailure(RedirectResolver::isStaleConnection).retry().atMost(1);
    }

    private Uni<HopComputation> fetchHop(URI target, WalkState state, HopVia via, long timeoutMs) {
        guard.checkUrl(target);
        long hopStartNanos = System.nanoTime();
        LOG.debugf("HEAD %s (timeoutMs=%d)", target, timeoutMs);

        AtomicReference<String> lastMethodAttempted = new AtomicReference<>("HEAD");
        // Shared by every path that can need a GET on this hop (a HEAD that never answers,
        // a HEAD stuck behind a stale pooled connection, the hedge firing, or the existing
        // fallback-status/HTML-scan cases below) so a hop never sends more than one GET no
        // matter which of those triggers races for it first.
        Uni<HttpResponse<Buffer>> getOnce = withStaleConnectionRetry(Uni.createFrom()
                .deferred(() -> {
                    lastMethodAttempted.set("GET");
                    return sendRequest(target, "GET", state, remainingHopMs(hopStartNanos, timeoutMs));
                }))
                .memoize().indefinitely();

        Uni<MethodResponse> headLeg = withStaleConnectionRetry(Uni.createFrom()
                        .deferred(() -> sendRequest(target, "HEAD", state, remainingHopMs(hopStartNanos, timeoutMs))))
                .<MethodResponse>map(response -> new MethodResponse("HEAD", response))
                // A HEAD that fails outright (dl.flipkart.com's /s/ links: connection opens,
                // HEAD is never answered) gets its GET immediately rather than waiting out the
                // hedge delay below. A DNS failure is fatal for the GET too, so it propagates
                // as-is instead of paying for a doomed request.
                .onFailure().recoverWithUni(t -> isDnsFailure(t)
                        ? Uni.createFrom().failure(t)
                        : getOnce.<MethodResponse>map(response -> new MethodResponse("GET", response)));

        long hedgeDelayMs = Math.min(ResolverLimits.HEDGE_DELAY_MS, timeoutMs / 2);
        Uni<MethodResponse> hedgeLeg = Uni.createFrom()
                .<Void>emitter(emitter -> {
                    long timerId = vertx.setTimer(hedgeDelayMs, id -> emitter.complete(null));
                    emitter.onTermination(() -> vertx.cancelTimer(timerId));
                })
                .flatMap(ignored -> getOnce)
                .<MethodResponse>map(response -> new MethodResponse("GET", response));

        return Uni.combine().any().of(headLeg, hedgeLeg)
                .flatMap(methodResponse -> {
                    if (!"HEAD".equals(methodResponse.method())) {
                        return Uni.createFrom().item(methodResponse);
                    }
                    HttpResponse<Buffer> headResponse = methodResponse.response();
                    boolean fallbackStatus = GET_FALLBACK_STATUSES.contains(headResponse.statusCode());
                    // HEAD never carries a body per the HTTP spec, so a terminal-looking 200 HTML
                    // response needs an actual GET before we can scan it for meta-refresh/JS intent.
                    boolean needsBodyForHtmlScan = headResponse.statusCode() == 200 && isHtml(headResponse.getHeader("Content-Type"));
                    if (!fallbackStatus && !needsBodyForHtmlScan) {
                        return Uni.createFrom().item(methodResponse);
                    }
                    LOG.debugf("HEAD %s -> %d, falling back to GET (fallbackStatus=%s, htmlScan=%s)",
                            target, headResponse.statusCode(), fallbackStatus, needsBodyForHtmlScan);
                    return getOnce.<MethodResponse>map(getResponse -> new MethodResponse("GET", getResponse));
                })
                .map(methodResponse -> interpretSafely(target, methodResponse.method(), methodResponse.response(), via, state, hopStartNanos))
                .onFailure().invoke(t -> LOG.debugf(t, "hop failed for %s", target))
                .onFailure().recoverWithItem(t -> errorComputation(target, via, hopStartNanos, t, lastMethodAttempted.get()));
    }

    /**
     * Bounds a retried HEAD or a fallback/hedge GET to what's actually left of the hop's own
     * timeout, not a fresh full window. Floors at 1ms, not {@code FLOOR_MS}: Vert.x's
     * {@code request.timeout(ms)} treats 0 (or negative) as "no timeout," so a positive floor
     * is required, but flooring at the hop-level {@code FLOOR_MS} let a request that had, say,
     * 50ms left run for up to 400ms instead of failing fast.
     */
    private long remainingHopMs(long hopStartNanos, long timeoutMs) {
        return Math.max(1, timeoutMs - elapsedMs(hopStartNanos));
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
            state.cookieHeaderFor(target.getHost()).ifPresent(c -> request.putHeader("Cookie", c));
        }
        if ("GET".equals(method)) {
            request.putHeader("Range", "bytes=0-" + (ResolverLimits.MAX_BODY_BYTES - 1));
        }
        return request.timeout(timeoutMs).send();
    }

    /**
     * Wraps {@link #interpret} so a bug in our own response handling (cookie parsing,
     * meta-refresh/JS scanning, intent/market URL parsing) can't be conflated with a real
     * network failure — the outer {@code onFailure} below this method's call site only ever
     * sees genuine transport/DNS exceptions now, and {@code classifyError} never has to
     * guess whether a caught exception came from the wire or from a bug. Logged at ERROR,
     * unlike a real transport failure's DEBUG: this should never happen and needs fixing,
     * not silent retrying.
     */
    private HopComputation interpretSafely(URI target, String method, HttpResponse<Buffer> response, HopVia via,
            WalkState state, long hopStartNanos) {
        try {
            return interpret(target, method, response, via, state, hopStartNanos);
        } catch (RuntimeException e) {
            LOG.errorf(e, "internal error interpreting %s response for %s", method, target);
            Hop hop = hopOf(target, method, response.statusCode(), null, via, response.getHeader("Content-Type"),
                    elapsedMs(hopStartNanos));
            return new HopComputation(hop,
                    new HopOutcome.Terminal(new Destination.Unresolved(StopReason.INTERNAL_ERROR), StopReason.INTERNAL_ERROR),
                    false);
        }
    }

    /** Every {@code Hop} recorded for a response actually received shares these fields. */
    private static Hop hopOf(URI target, String method, int status, String location, HopVia via, String contentType,
            long elapsedMs) {
        return new Hop(target.toString(), method, status, location, via, null, contentType, elapsedMs);
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
            state.storeCookies(target.getHost(), setCookies);
        }
        // Cookie-bearing hops are not edge-cacheable: replaying from cache would silently
        // skip the Set-Cookie a later hop might depend on.
        boolean cacheable = setCookies.isEmpty();

        if (REDIRECT_STATUSES.contains(status)) {
            String location = response.getHeader("Location");
            Hop hop = hopOf(target, method, status, location, via, contentType, elapsedMs);
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
                Hop hop = hopOf(target, method, status, metaRefresh.get(), via, contentType, elapsedMs);
                Optional<URI> next = resolveLocation(target, metaRefresh.get());
                if (next.isPresent() && UrlNormalizer.isHttp(next.get())) {
                    return new HopComputation(hop,
                            new HopOutcome.Redirect(UrlNormalizer.normalizeHttp(next.get()), HopVia.META_REFRESH), cacheable);
                }
                return new HopComputation(hop,
                        new HopOutcome.Terminal(new Destination.Web(target), StopReason.TERMINAL_RESPONSE), cacheable);
            }
            Optional<String> jsRedirect = JsRedirectDetector.detect(body);
            if (jsRedirect.isPresent()) {
                LOG.infof("js_suspected at %s (extracted=%s)", target, jsRedirect.get());
                Hop hop = hopOf(target, method, status, null, via, contentType, elapsedMs);
                return new HopComputation(hop,
                        new HopOutcome.Terminal(new Destination.Web(target), StopReason.JS_SUSPECTED), cacheable);
            }
        }

        Hop hop = hopOf(target, method, status, null, via, contentType, elapsedMs);
        return new HopComputation(hop, new HopOutcome.Terminal(new Destination.Web(target), StopReason.TERMINAL_RESPONSE), cacheable);
    }

    /** Non-HTTP redirect targets (intent://, market://) are a terminal success, not an error. */
    private HopOutcome resolveRedirectTarget(URI from, String location) {
        Optional<URI> maybeResolved = resolveLocation(from, location);
        if (maybeResolved.isEmpty()) {
            return new HopOutcome.Terminal(new Destination.Web(from), StopReason.TERMINAL_RESPONSE);
        }
        URI resolved = maybeResolved.get();
        String scheme = resolved.getScheme();
        if (IntentUrlParser.isIntent(scheme)) {
            Optional<URI> fallback = IntentUrlParser.extractFallback(resolved.toString());
            return new HopOutcome.Terminal(new Destination.AppIntent(resolved, fallback), StopReason.NON_HTTP_SCHEME);
        }
        if (IntentUrlParser.isMarket(scheme)) {
            String packageId = IntentUrlParser.extractPackageId(resolved.toString())
                    .orElse(resolved.getSchemeSpecificPart());
            // A bare "market://" (or one whose id= value the parser fails to extract, then
            // whose scheme-specific part is itself blank) has no usable package id — that's
            // a degenerate response from the target, not a bug in our own parsing, so it's
            // Unresolved rather than a Destination.Store construction Store's own invariant
            // would otherwise reject.
            if (packageId == null || packageId.isBlank()) {
                return new HopOutcome.Terminal(new Destination.Unresolved(StopReason.NON_HTTP_SCHEME), StopReason.NON_HTTP_SCHEME);
            }
            return new HopOutcome.Terminal(new Destination.Store(packageId), StopReason.NON_HTTP_SCHEME);
        }
        if (!UrlNormalizer.isHttp(resolved)) {
            return new HopOutcome.Terminal(new Destination.Unresolved(StopReason.NON_HTTP_SCHEME), StopReason.NON_HTTP_SCHEME);
        }
        return new HopOutcome.Redirect(UrlNormalizer.normalizeHttp(resolved), HopVia.HTTP);
    }

    /** Resolves a Location/meta-refresh value against the current URL: relative paths, missing schemes, unencoded chars. */
    private Optional<URI> resolveLocation(URI base, String location) {
        String trimmed = location.trim();
        Optional<URI> parsed = tryParse(trimmed).or(() -> tryParse(trimmed.replace(" ", "%20")));
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(base.resolve(parsed.get()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<URI> tryParse(String value) {
        try {
            return Optional.of(new URI(value));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private HopComputation errorComputation(URI target, HopVia via, long hopStartNanos, Throwable t, String method) {
        long elapsedMs = elapsedMs(hopStartNanos);
        StopReason reason = classifyError(t);
        LOG.debugf("classified error for %s as %s: %s", target, reason, t.toString());
        Hop hop = new Hop(target.toString(), method, null, null, via, null, null, elapsedMs);
        return new HopComputation(hop, new HopOutcome.Terminal(new Destination.Unresolved(reason), reason), true);
    }

    private StopReason classifyError(Throwable t) {
        return isDnsFailure(t) ? StopReason.DNS_ERROR : StopReason.TRANSPORT_ERROR;
    }

    private static boolean isDnsFailure(Throwable t) {
        return hasCause(t, UnknownHostException.class);
    }

    private static boolean isStaleConnection(Throwable t) {
        return hasCause(t, HttpClosedException.class);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private Result buildResult(URI normalizedInput, WalkState state, HopOutcome.Terminal outcome, long startNanos) {
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
        return new Result(normalizedInput.toString(), finalUrl, outcome.destination(), status, outcome.reason(),
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
