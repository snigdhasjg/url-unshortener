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

    /**
     * A {@code Result} for an unexpected internal failure — shared by every "never throws"
     * safety net that needs to report {@code StopReason.INTERNAL_ERROR} (see
     * {@code RedirectResolver}/{@code ResolverService}), rather than each hand-assembling
     * this same shape. {@code hops} may be non-empty (whatever completed before the failure).
     */
    public static Result internalError(String originalUrl, String finalUrl, List<Hop> hops, long budgetRemainingMs) {
        boolean anyHopCompleted = !hops.isEmpty();
        return new Result(originalUrl, finalUrl, new Destination.Unresolved(StopReason.INTERNAL_ERROR),
                Status.from(StopReason.INTERNAL_ERROR, anyHopCompleted), StopReason.INTERNAL_ERROR, hops, 0,
                budgetRemainingMs, false, false);
    }
}
