package com.snigji.unshortener.service;

import com.snigji.unshortener.cache.WholeWalkCache;
import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import com.snigji.unshortener.domain.StopReason;
import com.snigji.unshortener.resolver.RedirectResolver;
import com.snigji.unshortener.resolver.ResolverLimits;
import com.snigji.unshortener.ua.UaProfile;
import com.snigji.unshortener.ua.UaProfileRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Facade shared by both {@code /api/v1/resolve} and {@code /api/v2/unshorten} —
 * one resolver, one cache, one code path. The compat endpoint is a projection of
 * the same {@link Result}, never a second implementation.
 */
@ApplicationScoped
public class ResolverService {

    private static final Logger LOG = Logger.getLogger(ResolverService.class);

    @Inject
    RedirectResolver redirectResolver;

    @Inject
    WholeWalkCache wholeWalkCache;

    @Inject
    UaProfileRegistry uaProfiles;

    public Uni<Result> resolve(URI url, String profileName) {
        UaProfile profile = uaProfiles.resolve(profileName);

        Optional<Result> cached = wholeWalkCache.get(url, profile.name());
        if (cached.isPresent()) {
            LOG.debugf("whole-walk cache hit for %s|%s", profile.name(), url);
            return Uni.createFrom().item(cached.get().withCached(true));
        }

        LOG.debugf("whole-walk cache miss for %s|%s, resolving", profile.name(), url);
        return redirectResolver.resolve(url, profile)
                .invoke(result -> wholeWalkCache.put(url, profile.name(), result))
                .onFailure().invoke(t -> LOG.errorf(t,
                        "resolve() failed unexpectedly for %s despite RedirectResolver's own safety net", url))
                .onFailure().recoverWithItem(t -> internalErrorResult(url));
    }

    /**
     * Backstop of last resort. {@code RedirectResolver.resolve()} already guarantees it
     * never throws, but this facade does its own work around that call — cache lookup and
     * write, UA profile resolution — so if any of that breaks, both REST resources still
     * need a proper {@link Result}, not a bare 500. Per project knowledge, the real v2
     * compat client ignores HTTP status entirely and requires success/unshortened_url in
     * the body, so this is the one scenario the whole Result/Status design exists to prevent.
     */
    private Result internalErrorResult(URI url) {
        String rendered = url.toString();
        return new Result(rendered, rendered, new Destination.Unresolved(StopReason.INTERNAL_ERROR),
                Status.from(StopReason.INTERNAL_ERROR, false), StopReason.INTERNAL_ERROR, List.of(), 0, 0, false,
                false);
    }

    /**
     * Backstop for a bug in the deadline arithmetic: the resolver's own budget
     * (4800ms) always leaves headroom under the resource-level 5000ms hard cutoff,
     * so this should never actually fire in normal operation. If it does, degrade
     * safely rather than breach the ceiling — logged at WARN since it signals a bug.
     */
    public Result hardCutoffFallback(URI url) {
        LOG.warnf("hard cutoff reached for %s — resolver budget arithmetic likely has a bug", url);
        String rendered = url.toString();
        return new Result(rendered, rendered, new Destination.Unresolved(StopReason.DEADLINE), Status.PARTIAL,
                StopReason.DEADLINE, List.of(), ResolverLimits.API_CEILING.toMillis(), 0, false, false);
    }
}
