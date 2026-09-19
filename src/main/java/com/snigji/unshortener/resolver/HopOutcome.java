package com.snigji.unshortener.resolver;

import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.HopVia;
import com.snigji.unshortener.domain.StopReason;

import java.net.URI;

/** What a single hop attempt produced: either continue walking, or stop. */
public sealed interface HopOutcome {

    record Redirect(URI next, HopVia via) implements HopOutcome {}

    record Terminal(Destination destination, StopReason reason) implements HopOutcome {}
}
