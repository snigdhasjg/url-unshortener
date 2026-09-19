package com.snigji.unshortener.resolver;

import com.snigji.unshortener.domain.Hop;
import com.snigji.unshortener.ua.UaProfile;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable per-request walk state. One absolute deadline is computed at request
 * entry and carried here; each hop gets whatever remains, capped per {@link ResolverLimits}.
 * Never shared or cached across requests — a fresh instance backs every top-level
 * {@code resolve()} call, including cache misses.
 */
public final class WalkState {

    private final String rawInput;
    private final UaProfile profile;
    private final Instant deadline;
    private final CookieJar cookieJar = new CookieJar();
    private final List<Hop> hops = new ArrayList<>();
    private final Set<URI> visited = new HashSet<>();

    public WalkState(String rawInput, UaProfile profile) {
        this.rawInput = rawInput;
        this.profile = profile;
        this.deadline = Instant.now().plus(ResolverLimits.RESOLVER_BUDGET);
    }

    public String rawInput() {
        return rawInput;
    }

    public UaProfile profile() {
        return profile;
    }

    public CookieJar cookieJar() {
        return cookieJar;
    }

    public long remainingMs() {
        return Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
    }

    public long hopTimeoutMs() {
        return Math.min(ResolverLimits.PER_HOP_CAP_MS, remainingMs());
    }

    public boolean belowFloor() {
        return remainingMs() < ResolverLimits.FLOOR_MS;
    }

    /** True if this is the first time {@code uri} has been the target of a hop. */
    public boolean markVisited(URI uri) {
        return visited.add(uri);
    }

    public int hopCount() {
        return hops.size();
    }

    public boolean atMaxHops() {
        return hops.size() >= ResolverLimits.MAX_HOPS;
    }

    public void addHop(Hop hop) {
        hops.add(hop);
    }

    public List<Hop> hops() {
        return List.copyOf(hops);
    }
}
