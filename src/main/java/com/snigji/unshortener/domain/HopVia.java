package com.snigji.unshortener.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum HopVia {
    INITIAL("initial"),
    HTTP("http"),
    META_REFRESH("meta-refresh");

    private final String wire;

    HopVia(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
