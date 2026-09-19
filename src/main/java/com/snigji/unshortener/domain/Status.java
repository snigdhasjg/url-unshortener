package com.snigji.unshortener.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Status {
    RESOLVED("resolved"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String wire;

    Status(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * {@code failed} is reserved for a walk that never completed a single hop
     * (couldn't even resolve/connect to the input URL). A transport or DNS error
     * discovered mid-chain still carries a usable {@code final_url} from the hops
     * already completed, so it is reported as {@code partial}, not {@code failed} —
     * this is a deliberate reading of the spec's rich-API definition ("couldn't
     * complete even the first hop") over the compat-endpoint table, which only
     * discusses the common case where those two stop reasons occur on hop 1.
     */
    public static Status from(StopReason reason, boolean anyHopCompleted) {
        return switch (reason) {
            case TERMINAL_RESPONSE, NON_HTTP_SCHEME -> RESOLVED;
            case TRANSPORT_ERROR, DNS_ERROR -> anyHopCompleted ? PARTIAL : FAILED;
            case DEADLINE, MAX_HOPS, LOOP, JS_SUSPECTED -> PARTIAL;
        };
    }
}
