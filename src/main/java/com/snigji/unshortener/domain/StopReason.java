package com.snigji.unshortener.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StopReason {
    TERMINAL_RESPONSE("terminal_response"),
    DEADLINE("deadline"),
    MAX_HOPS("max_hops"),
    LOOP("loop"),
    NON_HTTP_SCHEME("non_http_scheme"),
    JS_SUSPECTED("js_suspected"),
    TRANSPORT_ERROR("transport_error"),
    DNS_ERROR("dns_error");

    private final String wire;

    StopReason(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
