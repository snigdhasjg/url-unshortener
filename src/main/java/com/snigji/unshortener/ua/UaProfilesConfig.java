package com.snigji.unshortener.ua;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;

import java.util.Map;
import java.util.Optional;

/**
 * Profiles are named config, not hardcoded strings — see application.properties.
 * Shipping only "android" on day one shouldn't require surgery to add "desktop" later.
 */
@ConfigMapping(prefix = "ua-profiles")
public interface UaProfilesConfig {

    /** {@code @WithParentName} flattens this under the prefix: {@code ua-profiles.android.*}, not {@code ua-profiles.profiles.android.*}. */
    @WithParentName
    Map<String, Profile> profiles();

    interface Profile {
        String userAgent();

        Optional<String> secChUa();

        Optional<String> secChUaMobile();

        Optional<String> secChUaPlatform();

        Optional<String> accept();

        Optional<String> acceptLanguage();
    }
}
