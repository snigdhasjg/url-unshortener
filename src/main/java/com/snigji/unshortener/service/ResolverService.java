package com.snigji.unshortener.service;

import com.snigji.unshortener.cache.WholeWalkCache;
import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import com.snigji.unshortener.domain.StopReason;
import com.snigji.unshortener.resolver.RedirectResolver;
import com.snigji.unshortener.resolver.ResolverLimits;
import com.snigji.unshortener.resolver.UrlNormalizer;
import com.snigji.unshortener.ua.UaProfile;
import com.snigji.unshortener.ua.UaProfileRegistry;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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

    public Uni<Result> resolve(String rawUrl, Optional<String> profileName) {
        UaProfile profile = uaProfiles.resolve(profileName);
        URI normalized = normalizeOrThrow(rawUrl);
        String cacheKey = profile.name() + "|" + normalized;

        Optional<Result> cached = wholeWalkCache.get(cacheKey);
        if (cached.isPresent()) {
            return Uni.createFrom().item(cached.get().withCached(true));
        }

        return redirectResolver.resolve(rawUrl, profile)
                .invoke(result -> wholeWalkCache.put(cacheKey, result));
    }

    private URI normalizeOrThrow(String rawUrl) {
        try {
            return UrlNormalizer.normalizeInput(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    /**
     * Backstop for a bug in the deadline arithmetic: the resolver's own budget
     * (4800ms) always leaves headroom under the resource-level 5000ms hard cutoff,
     * so this should never actually fire in normal operation. If it does, degrade
     * safely rather than breach the ceiling — logged at WARN since it signals a bug.
     */
    public Result hardCutoffFallback(String rawUrl) {
        LOG.warnf("hard cutoff reached for %s — resolver budget arithmetic likely has a bug", rawUrl);
        return new Result(rawUrl, rawUrl, new Destination.Unresolved(StopReason.DEADLINE), Status.PARTIAL,
                StopReason.DEADLINE, List.of(), ResolverLimits.API_CEILING.toMillis(), 0, false, false);
    }
}
