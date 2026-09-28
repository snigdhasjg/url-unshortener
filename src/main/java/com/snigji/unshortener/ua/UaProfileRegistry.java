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
        // Profile names are config keys a client passes in a query param, not case-sensitive
        // identifiers — "Android" and "android" are the same profile. Returns the canonical
        // stored key (not the requested casing) so cache keys built from profile().name()
        // don't fragment across request casing.
        return config.profiles().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst()
                .map(entry -> UaProfile.from(entry.getKey(), entry.getValue()))
                .orElseThrow(() -> new BadRequestException("unknown profile: " + name));
    }
}
