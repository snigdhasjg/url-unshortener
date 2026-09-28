package com.snigji.unshortener.ua;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.Optional;

/**
 * Profiles are named config, not hardcoded strings — see {@code application.yml}'s
 * {@code ua-profiles} section, which today configures both {@code android} and
 * {@code desktop}.
 */
@ConfigMapping(prefix = "ua-profiles")
public interface UaProfilesConfig {

    /** {@code @WithParentName} flattens this under the prefix: {@code ua-profiles.android.*}, not {@code ua-profiles.profiles.android.*}. */
    @WithParentName
    Map<String, Profile> profiles();

    interface Profile {
        @NotBlank
        String userAgent();

        Optional<String> secChUa();

        Optional<String> secChUaMobile();

        Optional<String> secChUaPlatform();

        Optional<String> accept();

        Optional<String> acceptLanguage();
    }
}
