package com.snigji.unshortener.resolver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetaRefreshScannerTest {

    @Test
    void findsBasicMetaRefresh() {
        String html = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=https://example.com/final\"></head></html>";
        assertEquals(Optional.of("https://example.com/final"), MetaRefreshScanner.findRefreshUrl(html));
    }

    @Test
    void handlesSingleQuotesAndUnquotedContent() {
        String html = "<meta http-equiv='refresh' content='5; url=/relative/path'>";
        assertEquals(Optional.of("/relative/path"), MetaRefreshScanner.findRefreshUrl(html));
    }

    @Test
    void handlesAttributeOrderReversed() {
        String html = "<meta content=\"0;url=https://example.com/x\" http-equiv=\"refresh\">";
        assertEquals(Optional.of("https://example.com/x"), MetaRefreshScanner.findRefreshUrl(html));
    }

    @Test
    void unescapesEntities() {
        String html = "<meta http-equiv=\"refresh\" content=\"0;url=https://example.com/x?a=1&amp;b=2\">";
        assertEquals(Optional.of("https://example.com/x?a=1&b=2"), MetaRefreshScanner.findRefreshUrl(html));
    }

    @Test
    void ignoresMetaTagsInsideComments() {
        String html = "<!-- <meta http-equiv=\"refresh\" content=\"0;url=https://evil.example/x\"> --><body>ok</body>";
        assertTrue(MetaRefreshScanner.findRefreshUrl(html).isEmpty());
    }

    @Test
    void ignoresUnrelatedMetaTags() {
        String html = "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">";
        assertTrue(MetaRefreshScanner.findRefreshUrl(html).isEmpty());
    }

    @Test
    void caseInsensitiveUrlKey() {
        String html = "<meta http-equiv=\"Refresh\" content=\"0; URL=https://example.com/x\">";
        assertEquals(Optional.of("https://example.com/x"), MetaRefreshScanner.findRefreshUrl(html));
    }
}
