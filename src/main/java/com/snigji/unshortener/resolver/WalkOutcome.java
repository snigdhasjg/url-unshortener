package com.snigji.unshortener.resolver;

import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.StopReason;

record WalkOutcome(Destination destination, StopReason reason) {
}
