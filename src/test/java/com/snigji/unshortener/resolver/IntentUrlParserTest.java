package com.snigji.unshortener.resolver;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentUrlParserTest {

    @Test
    void extractsBrowserFallbackUrl() {
        String raw = "intent://scan/#Intent;scheme=https;package=com.example;"
                + "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end";
        assertEquals(Optional.of(URI.create("https://example.com/fallback")), IntentUrlParser.extractFallback(raw));
    }

    @Test
    void emptyWhenNoFallbackPresent() {
        String raw = "intent://scan/#Intent;scheme=https;package=com.example;end";
        assertTrue(IntentUrlParser.extractFallback(raw).isEmpty());
    }

    @Test
    void extractsMarketPackageId() {
        assertEquals(Optional.of("com.example.app"),
                IntentUrlParser.extractPackageId("market://details?id=com.example.app"));
    }

    @Test
    void recognizesSchemes() {
        assertTrue(IntentUrlParser.isIntent("intent"));
        assertTrue(IntentUrlParser.isIntent("INTENT"));
        assertTrue(IntentUrlParser.isMarket("market"));
        assertTrue(!IntentUrlParser.isIntent("https") && !IntentUrlParser.isMarket("https"));
    }
}
