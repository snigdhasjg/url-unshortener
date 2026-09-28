package com.snigji.unshortener.service;

import com.snigji.unshortener.cache.WholeWalkCache;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import com.snigji.unshortener.domain.StopReason;
import com.snigji.unshortener.resolver.RedirectResolver;
import com.snigji.unshortener.ua.UaProfile;
import com.snigji.unshortener.ua.UaProfileRegistry;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the Tier 2 #2 backstop directly: even if {@code RedirectResolver.resolve()}
 * itself fails (a bug that got past its own safety net, or a fault anywhere else in this
 * facade), {@code ResolverService.resolve()} must still hand back a proper {@link Result}
 * rather than let the failure propagate to the generic REST-layer 500.
 */
class ResolverServiceTest {

    private static final UaProfile PROFILE = new UaProfile("android", List.of(Map.entry("User-Agent", "test-agent")));

    @Test
    void recoversWithAProperResultWhenTheResolverFailsUnexpectedly() {
        ResolverService service = new ResolverService();
        service.wholeWalkCache = new WholeWalkCache();
        service.uaProfiles = new UaProfileRegistry() {
            @Override
            public UaProfile resolve(Optional<String> requestedName) {
                return PROFILE;
            }
        };
        service.redirectResolver = new RedirectResolver() {
            @Override
            public Uni<Result> resolve(URI url, UaProfile profile) {
                return Uni.createFrom().failure(new IllegalStateException("boom"));
            }
        };

        Result result = service.resolve(URI.create("https://example.com/x"), Optional.empty())
                .await().atMost(Duration.ofSeconds(5));

        assertEquals(Status.FAILED, result.status());
        assertEquals(StopReason.INTERNAL_ERROR, result.stopReason());
        assertTrue(result.hops().isEmpty());
    }
}
