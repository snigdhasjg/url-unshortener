package com.snigji.unshortener.rest;

import com.fasterxml.jackson.annotation.JsonInclude;

/** unshorten.me-compatible response shape. {@code error} is a superset field — omitted on success. */
public record UnshortenResponse(
        String unshortenedUrl,
        String shortenedUrl,
        boolean success,
        @JsonInclude(JsonInclude.Include.NON_NULL) String error) {

    public static UnshortenResponse success(String unshortenedUrl, String shortenedUrl) {
        return new UnshortenResponse(unshortenedUrl, shortenedUrl, true, null);
    }

    public static UnshortenResponse failure(String rawInput, String error) {
        return new UnshortenResponse(rawInput, rawInput, false, error);
    }
}
