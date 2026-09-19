package com.snigji.unshortener.resolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Per-resolution cookie jar (never shared across requests — a fresh instance lives
 * on each {@link WalkState}). Scopes cookies by the {@code Domain} attribute when
 * present, else the responding host. Not a full RFC 6265 implementation: Path,
 * Expires/Max-Age and Secure/HttpOnly are ignored, which is enough for the "hop 1
 * sets a cookie, hop 2 on the same site requires it" case this exists for.
 */
public final class CookieJar {

    private final Map<String, Map<String, String>> byDomain = new LinkedHashMap<>();

    public void store(String responseHost, List<String> setCookieHeaders) {
        if (setCookieHeaders == null) {
            return;
        }
        for (String header : setCookieHeaders) {
            String[] nameValue = header.split(";", 2)[0].split("=", 2);
            if (nameValue.length != 2) {
                continue;
            }
            String domain = extractDomain(header).orElse(responseHost).toLowerCase(Locale.ROOT);
            byDomain.computeIfAbsent(domain, d -> new LinkedHashMap<>())
                    .put(nameValue[0].trim(), nameValue[1].trim());
        }
    }

    private Optional<String> extractDomain(String setCookieHeader) {
        for (String attr : setCookieHeader.split(";")) {
            String trimmed = attr.trim();
            if (trimmed.regionMatches(true, 0, "Domain=", 0, 7)) {
                String domain = trimmed.substring(7).trim();
                return domain.startsWith(".") ? Optional.of(domain.substring(1)) : Optional.of(domain);
            }
        }
        return Optional.empty();
    }

    public Optional<String> cookieHeaderFor(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        Map<String, String> matched = new LinkedHashMap<>();
        for (var entry : byDomain.entrySet()) {
            String domain = entry.getKey();
            if (h.equals(domain) || h.endsWith("." + domain)) {
                matched.putAll(entry.getValue());
            }
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        matched.forEach((k, v) -> {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(k).append('=').append(v);
        });
        return Optional.of(sb.toString());
    }
}
