package com.snigji.unshortener.ua;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Resolved header set for a named profile, in the exact order a real browser would send them. */
public record UaProfile(String name, List<Map.Entry<String, String>> headers) {

    /**
     * Defensively copies {@code headers} so immutability holds regardless of entry point —
     * previously only {@link #from} did this, so constructing a {@code UaProfile} directly
     * with a mutable list (as tests already do) silently bypassed it.
     */
    public UaProfile {
        headers = List.copyOf(headers);
    }

    public static UaProfile from(String name, UaProfilesConfig.Profile config) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Map.entry("User-Agent", config.userAgent()));
        config.secChUa().ifPresent(v -> headers.add(Map.entry("Sec-CH-UA", v)));
        config.secChUaMobile().ifPresent(v -> headers.add(Map.entry("Sec-CH-UA-Mobile", v)));
        config.secChUaPlatform().ifPresent(v -> headers.add(Map.entry("Sec-CH-UA-Platform", v)));
        config.accept().ifPresent(v -> headers.add(Map.entry("Accept", v)));
        config.acceptLanguage().ifPresent(v -> headers.add(Map.entry("Accept-Language", v)));
        return new UaProfile(name, headers);
    }
}
