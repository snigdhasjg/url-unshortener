package com.snigji.unshortener.domain;

import java.net.URI;

/**
 * The terminal outcome of a redirect walk. Jackson serializes whichever record
 * is actually held here by its own field names — no discriminator, since callers
 * of this API always know which endpoint (and therefore which shape) they asked for.
 */
public sealed interface Destination {

    record Web(URI uri) implements Destination {}

    /** An {@code intent://} target. {@code fallback} is the extracted {@code S.browser_fallback_url}, if any. */
    record AppIntent(URI raw, URI fallback) implements Destination {}

    /** A {@code market://details?id=...} target. */
    record Store(String packageId) implements Destination {}

    record Unresolved(StopReason reason) implements Destination {}
}
