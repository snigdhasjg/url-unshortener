package com.snigji.unshortener.resolver;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code intent://...#Intent;key=value;...;end} strings to extract the
 * embedded {@code S.browser_fallback_url}, and recognizes {@code market://details?id=...}.
 */
public final class IntentUrlParser {

    private static final String FALLBACK_KEY = "S.browser_fallback_url=";
    private static final Pattern MARKET_ID = Pattern.compile("[?&]id=([^&]+)", Pattern.CASE_INSENSITIVE);

    private IntentUrlParser() {
    }

    public static boolean isIntent(String scheme) {
        return "intent".equalsIgnoreCase(scheme);
    }

    public static boolean isMarket(String scheme) {
        return "market".equalsIgnoreCase(scheme);
    }

    public static Optional<URI> extractFallback(String rawIntentUrl) {
        int hashIdx = rawIntentUrl.indexOf('#');
        if (hashIdx < 0) {
            return Optional.empty();
        }
        String fragment = rawIntentUrl.substring(hashIdx + 1);
        for (String part : fragment.split(";")) {
            if (part.startsWith(FALLBACK_KEY)) {
                String encoded = part.substring(FALLBACK_KEY.length());
                try {
                    String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                    return Optional.of(URI.create(decoded));
                } catch (Exception e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<String> extractPackageId(String rawMarketUrl) {
        Matcher m = MARKET_ID.matcher(rawMarketUrl);
        if (m.find()) {
            return Optional.of(URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }
}
