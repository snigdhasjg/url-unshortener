package com.snigji.unshortener.resolver;

import jakarta.ws.rs.BadRequestException;
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
        assertThrows(BadRequestException.class, () -> UrlNormalizer.normalizeInput("ftp://example.com/x"));
        assertThrows(BadRequestException.class, () -> UrlNormalizer.normalizeInput("intent://scan/#Intent;end"));
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(BadRequestException.class, () -> UrlNormalizer.normalizeInput(""));
        assertThrows(BadRequestException.class, () -> UrlNormalizer.normalizeInput("   "));
    }

    @Test
    void preservesEncodedDelimitersInQuery() {
        URI original = URI.create("https://t.co/r?u=https%3A%2F%2Fx.com%2Fa%3Fb%3D1%26c%3D2&k=v");
        assertEquals(original, UrlNormalizer.normalizeHttp(original));
    }

    @Test
    void preservesEncodedSlashAndPlusInPath() {
        assertEquals(URI.create("https://example.com/a%2Fb"),
                UrlNormalizer.normalizeHttp(URI.create("https://example.com/a%2Fb")));
        assertEquals(URI.create("https://example.com/s?q=a%2Bb"),
                UrlNormalizer.normalizeHttp(URI.create("https://example.com/s?q=a%2Bb")));
    }

    @Test
    void preservesPercentEncodedUtf8() {
        assertEquals(URI.create("https://example.com/caf%C3%A9"),
                UrlNormalizer.normalizeHttp(URI.create("https://example.com/caf%C3%A9")));
    }

    @Test
    void returnsUnparseableAuthorityUnchanged() {
        URI underscoreHost = URI.create("http://ex_ample.com/x");
        assertEquals(underscoreHost, UrlNormalizer.normalizeHttp(underscoreHost));
    }

    @Test
    void stripsDefaultPortOnIpv6Host() {
        assertEquals(URI.create("https://[2001:db8::1]/x"),
                UrlNormalizer.normalizeHttp(URI.create("https://[2001:DB8::1]:443/x")));
    }
}
