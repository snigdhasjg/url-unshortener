package com.snigji.unshortener.resolver;

import com.snigji.unshortener.domain.Hop;

/**
 * The full result of computing one hop: the diagnostic {@link Hop} record for the
 * response, what to do next, and whether it's safe to park in the edge cache
 * (false when the response carried {@code Set-Cookie} — replaying it from cache
 * would silently skip setting that cookie on a future walk).
 */
public record HopComputation(Hop hop, HopOutcome outcome, boolean cacheable) {
}
