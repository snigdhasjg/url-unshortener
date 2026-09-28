package com.snigji.unshortener.domain;

import java.util.List;

public record Result(
        String originalUrl,
        String finalUrl,
        Destination destination,
        Status status,
        StopReason stopReason,
        List<Hop> hops,
        long elapsedMs,
        long budgetRemainingMs,
        boolean cached,
        boolean resumable) {

    public Result {
        if (!Status.isConsistent(status, stopReason)) {
            throw new IllegalArgumentException("status " + status + " is inconsistent with stopReason " + stopReason);
        }
    }

    public Result withCached(boolean cachedValue) {
        return new Result(originalUrl, finalUrl, destination, status, stopReason, hops,
                elapsedMs, budgetRemainingMs, cachedValue, resumable);
    }
}
