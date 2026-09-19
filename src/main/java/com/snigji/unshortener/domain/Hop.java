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
}
