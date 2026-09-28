package com.snigji.unshortener.resolver;

import java.time.Duration;

public final class ResolverLimits {

    /** Absolute API ceiling. Never breach this — see {@code ResolveResource}'s hard cutoff. */
    public static final Duration API_CEILING = Duration.ofMillis(5000);

    public static final Duration RESPONSE_RESERVE = Duration.ofMillis(200);

    /** Budget actually handed to the resolver; the remainder is {@link #RESPONSE_RESERVE}. */
    public static final Duration RESOLVER_BUDGET = API_CEILING.minus(RESPONSE_RESERVE);

    public static final long PER_HOP_CAP_MS = 1500;

    /**
     * How long to wait for HEAD before racing it against a GET on the same hop. Set above
     * the ~600ms band observed for healthy shorteners answering HEAD, so the hedge fires
     * only for hosts that black-hole HEAD entirely (e.g. dl.flipkart.com's /s/ short links).
     */
    public static final long HEDGE_DELAY_MS = 700;

    /** Below this much remaining budget, don't bother attempting another hop. */
    public static final long FLOOR_MS = 400;

    public static final int MAX_HOPS = 10;

    public static final long MAX_BODY_BYTES = 64 * 1024;

    /** {@code WebClientProducer}'s connect timeout. */
    public static final int CONNECT_TIMEOUT_MS = 2000;

    /** {@code WebClientProducer}'s max connection pool size. */
    public static final int MAX_POOL_SIZE = 64;

    // Fails class-load, not just documentation, if these constants drift out of the
    // relationship their own comments claim. Plain `assert` isn't used: that's a no-op
    // unless the JVM runs with -ea, and this needs to hold in production too. The first
    // check is now tautological — RESOLVER_BUDGET is defined in terms of RESPONSE_RESERVE
    // rather than as an independent literal — but it's cheap insurance against a future
    // edit reverting that back to a literal.
    static {
        if (!RESOLVER_BUDGET.plus(RESPONSE_RESERVE).equals(API_CEILING)) {
            throw new ExceptionInInitializerError("RESOLVER_BUDGET + RESPONSE_RESERVE must equal API_CEILING");
        }
        if (FLOOR_MS >= PER_HOP_CAP_MS) {
            throw new ExceptionInInitializerError("FLOOR_MS must be less than PER_HOP_CAP_MS");
        }
    }

    private ResolverLimits() {
    }
}
