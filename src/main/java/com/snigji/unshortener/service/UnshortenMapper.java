package com.snigji.unshortener.service;

import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;

/**
 * Projects the rich {@link Result} onto the unshorten.me-compatible shape.
 * {@code success} maps to {@code status != failed}, no other condition — a partial
 * carries the best-known {@code final_url}, which can equal the input verbatim (e.g.
 * {@code js_suspected} on the very first hop). A client that cares whether the chain
 * actually advanced should compare {@code unshortened_url} against {@code shortened_url}.
 */
@ApplicationScoped
public class UnshortenMapper {

    public UnshortenResponse toCompat(Result result, String rawInput) {
        if (result.status() == Status.FAILED) {
            return UnshortenResponse.failure(rawInput, result.stopReason().wire());
        }
        return UnshortenResponse.success(deepLinkAwareUrl(result), rawInput);
    }

    /**
     * A compat client expects an http(s) URL, so non-HTTP destinations resolve in
     * order: the intent's browser_fallback_url, then the Play Store URL for a
     * market:// package id, then the raw opaque scheme as a last resort.
     */
    private String deepLinkAwareUrl(Result result) {
        return switch (result.destination()) {
            case Destination.AppIntent appIntent -> appIntent.fallback()
                    .map(URI::toString)
                    .orElseGet(() -> appIntent.raw().toString());
            case Destination.Store store -> "https://play.google.com/store/apps/details?id=" + store.packageId();
            case Destination.Web ignored -> result.finalUrl();
            case Destination.Unresolved ignored -> result.finalUrl();
        };
    }
}
