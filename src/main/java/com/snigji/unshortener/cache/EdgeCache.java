package com.snigji.unshortener.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.snigji.unshortener.domain.StopReason;
import com.snigji.unshortener.resolver.HopComputation;
import com.snigji.unshortener.resolver.HopOutcome;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * L2: per-edge cache, key {@code (url, ua_profile) -> next}. Makes partial results
 * resumable — a retry after {@code deadline} replays the known prefix in
 * microseconds and spends the full budget on new ground. Also shares entries
 * across unrelated links funneling through the same tracker/affiliate domain.
 *
 * <p>Entries where the response set a cookie are never stored here (see
 * {@link HopComputation#cacheable()}) — see {@code CookieJar} for why.
 */
@ApplicationScoped
public class EdgeCache {

    private final Cache<String, HopComputation> success = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(7))
            .maximumSize(50_000)
            .build();

    private final Cache<String, HopComputation> failure = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(50_000)
            .build();

    private static String key(URI url, String profile) {
        return profile + "|" + url;
    }

    public Optional<HopComputation> get(URI url, String profile) {
        String k = key(url, profile);
        HopComputation computation = success.getIfPresent(k);
        if (computation == null) {
            computation = failure.getIfPresent(k);
        }
        return Optional.ofNullable(computation);
    }

    public void put(URI url, String profile, HopComputation computation) {
        if (!computation.cacheable()) {
            return;
        }
        boolean isTransientError = computation.outcome() instanceof HopOutcome.Terminal terminal
                && (terminal.reason() == StopReason.TRANSPORT_ERROR || terminal.reason() == StopReason.DNS_ERROR);
        (isTransientError ? failure : success).put(key(url, profile), computation);
    }

    /** Used to compute {@code Result.resumable}: is the next hop we didn't get to already warm? */
    public boolean isWarm(URI url, String profile) {
        return get(url, profile).isPresent();
    }
}
