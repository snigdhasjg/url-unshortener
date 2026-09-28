package com.snigji.unshortener.domain;

public record Hop(
        String url,
        String method,
        Integer statusCode,
        String location,
        HopVia via,
        String remoteIp,
        String contentType,
        long elapsedMs) {

    /** {@code via} describes how a walk reached this hop, so a cache hit needs its own copy. */
    public Hop withVia(HopVia via) {
        return new Hop(url, method, statusCode, location, via, remoteIp, contentType, elapsedMs);
    }
}
