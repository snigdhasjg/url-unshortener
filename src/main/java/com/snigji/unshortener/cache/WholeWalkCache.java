package com.snigji.unshortener.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Optional;

/**
 * L1: whole-walk cache, key {@code (normalized_url, ua_profile)}. Fast path for
 * exact repeats.
 *
 * <p>{@code @CacheResult} isn't used here: it doesn't support a per-entry TTL that
 * depends on the value (7d for success/partial, 5min for failure), and Quarkus's
 * Caffeine config only accepts one static {@code expire-after-write} per named
 * cache. Two physical caches, checked in order, gets us the asymmetric TTL —
 * exactly what the plan calls out as the alternative to an {@code Expiry} policy bean.
 */
@ApplicationScoped
public class WholeWalkCache {

    private final Cache<String, Result> success = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofDays(7))
            .maximumSize(10_000)
            .build();

    private final Cache<String, Result> failure = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    public Optional<Result> get(String key) {
        Result result = success.getIfPresent(key);
        if (result == null) {
            result = failure.getIfPresent(key);
        }
        return Optional.ofNullable(result);
    }

    /** Never throws on a resolution failure — {@code Result} always carries success/failure explicitly. */
    public void put(String key, Result result) {
        (result.status() == Status.FAILED ? failure : success).put(key, result);
    }
}
