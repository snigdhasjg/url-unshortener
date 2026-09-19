package com.snigji.unshortener.service;

import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import com.snigji.unshortener.rest.UnshortenResponse;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Projects the rich {@link Result} onto the unshorten.me-compatible shape.
 * {@code success} maps to {@code status != failed}, no other condition — a partial
 * always carries at least one successful hop and a final_url better than the input.
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
            case Destination.AppIntent appIntent -> appIntent.fallback() != null
                    ? appIntent.fallback().toString()
                    : appIntent.raw().toString();
            case Destination.Store store -> "https://play.google.com/store/apps/details?id=" + store.packageId();
            case Destination.Web ignored -> result.finalUrl();
            case Destination.Unresolved ignored -> result.finalUrl();
        };
    }
}
