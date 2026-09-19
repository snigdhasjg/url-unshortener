package com.snigji.unshortener.resolver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsRedirectDetectorTest {

    @Test
    void detectsLocationReplace() {
        String html = "<script>location.replace('https://example.com/final');</script>";
        assertEquals(Optional.of("https://example.com/final"), JsRedirectDetector.detect(html));
    }

    @Test
    void detectsWindowLocationAssignment() {
        String html = "<script>window.location = \"https://example.com/final\";</script>";
        assertEquals(Optional.of("https://example.com/final"), JsRedirectDetector.detect(html));
    }

    @Test
    void detectsNoscriptLink() {
        String html = "<noscript><a href=\"https://example.com/final\">continue</a></noscript>";
        assertEquals(Optional.of("https://example.com/final"), JsRedirectDetector.detect(html));
    }

    @Test
    void detectsAutoSubmittingForm() {
        String html = "<form id=\"f\" action=\"https://example.com/final\" method=\"get\"></form>"
                + "<script>document.getElementById('f').submit();</script>";
        assertEquals(Optional.of("https://example.com/final"), JsRedirectDetector.detect(html));
    }

    @Test
    void doesNotFlagOrdinaryHtml() {
        assertTrue(JsRedirectDetector.detect("<html><body>hello</body></html>").isEmpty());
    }

    @Test
    void flagsPresenceEvenWithoutExtractableLiteral() {
        String html = "<script>window.location = buildUrl();</script>";
        Optional<String> result = JsRedirectDetector.detect(html);
        assertTrue(result.isPresent());
        assertEquals("", result.get());
    }
}
