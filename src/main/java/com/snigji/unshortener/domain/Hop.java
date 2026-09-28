package com.snigji.unshortener.domain;

public record Hop(
        String url,
        String method,
        Integer statusCode,
        String location,
        HopVia via,
        // Always null today — Vert.x's WebClient response doesn't expose the underlying
        // connection without dropping to raw HttpClient. A known gap, not a bug; see
        // CLAUDE.md's "Not yet done".
        String remoteIp,
        String contentType,
        long elapsedMs) {

    /** {@code via} describes how a walk reached this hop, so a cache hit needs its own copy. */
    public Hop withVia(HopVia via) {
        return new Hop(url, method, statusCode, location, via, remoteIp, contentType, elapsedMs);
    }
}
