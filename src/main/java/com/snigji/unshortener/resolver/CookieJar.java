package com.snigji.unshortener.resolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
        for (String header : setCookieHeaders) {
            String[] nameValue = header.split(";", 2)[0].split("=", 2);
            if (nameValue.length != 2) {
                // A Set-Cookie without a "name=value" first segment is malformed — skip it,
                // intentionally, rather than treat one bad cookie as a hard failure.
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
            if (trimmed.regionMatches(true, 0, "Domain=", 0, "Domain=".length())) {
                String domain = trimmed.substring("Domain=".length()).trim();
                if (domain.startsWith(".")) {
                    domain = domain.substring(1);
                }
                // An explicit-but-empty Domain (`Domain=;` or `Domain=.`) is present but
                // unusable — treat it the same as absent so store() falls back to the
                // responding host instead of filing the cookie under domain "".
                return domain.isEmpty() ? Optional.empty() : Optional.of(domain);
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
        String header = matched.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        return Optional.of(header);
    }
}
