package com.snigji.unshortener.domain;

import java.net.URI;
import java.util.Optional;

/**
 * The terminal outcome of a redirect walk. Jackson serializes whichever record
 * is actually held here by its own field names — no discriminator, since callers
 * of this API always know which endpoint (and therefore which shape) they asked for.
 */
public sealed interface Destination {

    record Web(URI uri) implements Destination {}

    /**
     * An {@code intent://} target. {@code fallback} is the extracted
     * {@code S.browser_fallback_url}, if any — {@code Optional}, not a nullable {@code URI},
     * consistent with how an absent value is modeled elsewhere (e.g. {@code UaProfilesConfig.Profile}).
     */
    record AppIntent(URI raw, Optional<URI> fallback) implements Destination {}

    /** A {@code market://details?id=...} target. */
    record Store(String packageId) implements Destination {
        public Store {
            if (packageId == null || packageId.isBlank()) {
                throw new IllegalArgumentException("packageId must not be blank");
            }
        }
    }

    record Unresolved(StopReason reason) implements Destination {}
}
