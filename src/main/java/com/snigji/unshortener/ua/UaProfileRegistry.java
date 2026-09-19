package com.snigji.unshortener.ua;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

@ApplicationScoped
public class UaProfileRegistry {

    @Inject
    UaProfilesConfig config;

    @ConfigProperty(name = "resolver.default-profile", defaultValue = "android")
    String defaultProfileName;

    public UaProfile resolve(Optional<String> requestedName) {
        String name = requestedName.filter(s -> !s.isBlank()).orElse(defaultProfileName);
        UaProfilesConfig.Profile profileConfig = config.profiles().get(name);
        if (profileConfig == null) {
            throw new BadRequestException("unknown profile: " + name);
        }
        return UaProfile.from(name, profileConfig);
    }
}
