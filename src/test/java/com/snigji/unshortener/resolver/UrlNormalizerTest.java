package com.snigji.unshortener.resolver;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlNormalizerTest {

    @Test
    void assumesHttpsWhenSchemeMissing() {
        assertEquals(URI.create("https://bit.ly/abc"), UrlNormalizer.normalizeInput("bit.ly/abc"));
    }

    @Test
    void lowercasesSchemeAndHostButNotPath() {
        assertEquals(URI.create("https://example.com/AbC"), UrlNormalizer.normalizeInput("HTTPS://EXAMPLE.COM/AbC"));
    }

    @Test
    void stripsDefaultPorts() {
        assertEquals(URI.create("https://example.com/x"), UrlNormalizer.normalizeInput("https://example.com:443/x"));
        assertEquals(URI.create("http://example.com/x"), UrlNormalizer.normalizeInput("http://example.com:80/x"));
    }

    @Test
    void keepsNonDefaultPorts() {
        assertEquals(URI.create("https://example.com:8443/x"), UrlNormalizer.normalizeInput("https://example.com:8443/x"));
    }

    @Test
    void dropsFragment() {
        assertEquals(URI.create("https://example.com/x"), UrlNormalizer.normalizeInput("https://example.com/x#section"));
    }

    @Test
    void rejectsNonHttpSchemeOnInput() {
        assertThrows(IllegalArgumentException.class, () -> UrlNormalizer.normalizeInput("ftp://example.com/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlNormalizer.normalizeInput("intent://scan/#Intent;end"));
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> UrlNormalizer.normalizeInput(""));
        assertThrows(IllegalArgumentException.class, () -> UrlNormalizer.normalizeInput("   "));
    }
}
