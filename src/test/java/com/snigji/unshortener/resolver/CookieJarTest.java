package com.snigji.unshortener.resolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieJarTest {

    @Test
    void explicitButEmptyDomainFallsBackToResponseHost() {
        CookieJar jar = new CookieJar();
        jar.store("example.com", List.of("session=abc; Domain=;"));
        assertEquals("session=abc", jar.cookieHeaderFor("example.com").orElseThrow());
    }

    @Test
    void bareDotDomainFallsBackToResponseHost() {
        CookieJar jar = new CookieJar();
        jar.store("example.com", List.of("session=abc; Domain=."));
        assertEquals("session=abc", jar.cookieHeaderFor("example.com").orElseThrow());
    }

    @Test
    void leadingDotDomainStillMatchesSubdomains() {
        CookieJar jar = new CookieJar();
        jar.store("www.example.com", List.of("session=abc; Domain=.example.com"));
        assertTrue(jar.cookieHeaderFor("sub.example.com").isPresent());
        assertEquals("session=abc", jar.cookieHeaderFor("sub.example.com").orElseThrow());
    }
}
