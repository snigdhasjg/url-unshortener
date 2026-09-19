package com.snigji.unshortener.security;

import java.net.InetAddress;
import java.net.URI;

/**
 * Deliberate deferral: this service fetches attacker-controlled URLs from inside a
 * home LAN, so whoever creates a short link controls every hop the fetcher makes.
 * The real mitigation is IP denylisting (RFC1918, 169.254/16, IPv6 equivalents)
 * validated at *every* hop with the IP pinned between check and connect, plus
 * network isolation at the firewall — not implemented yet, but the hook must be
 * structural so wiring in a real policy later is a one-class change, not a
 * refactor of the HTTP client setup.
 */
public interface Guard {

    /** Called for every URL about to be fetched, before the request is issued. */
    void checkUrl(URI url);

    /**
     * Called for every candidate address a host resolves to, before connecting,
     * with the IP pinned so it can't change between check and connect.
     *
     * <p>Not yet wired: Vert.x's {@code WebClient} resolves and connects in one
     * step, so calling this for real requires a custom address resolver (see
     * {@code WebClientOptions#setAddressResolverOptions}) or a manual DNS lookup
     * ahead of a pinned connection. Left as a no-op call site rather than faked.
     */
    void checkIp(String host, InetAddress ip);
}
