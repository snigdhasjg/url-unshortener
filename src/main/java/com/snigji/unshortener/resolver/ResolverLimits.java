package com.snigji.unshortener.resolver;

import java.time.Duration;

public final class ResolverLimits {

    /** Absolute API ceiling. Never breach this — see {@code ResolveResource}'s hard cutoff. */
    public static final Duration API_CEILING = Duration.ofMillis(5000);

    /** Budget actually handed to the resolver; the remainder is {@link #RESPONSE_RESERVE}. */
    public static final Duration RESOLVER_BUDGET = Duration.ofMillis(4800);

    public static final Duration RESPONSE_RESERVE = Duration.ofMillis(200);

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

    private ResolverLimits() {
    }
}
