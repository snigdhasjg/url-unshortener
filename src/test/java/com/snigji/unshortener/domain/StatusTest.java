package com.snigji.unshortener.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusTest {

    @Test
    void internalErrorFollowsTheSameRuleAsTransportAndDnsErrors() {
        assertEquals(Status.FAILED, Status.from(StopReason.INTERNAL_ERROR, false));
        assertEquals(Status.PARTIAL, Status.from(StopReason.INTERNAL_ERROR, true));
    }
}
