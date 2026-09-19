package com.snigji.unshortener;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.security.Security;

/**
 * DNS must go through AdGuard, which has this container allowlisted (unfiltered
 * but logged) — its query log becomes a record of every domain a suspicious link
 * touched. Default JVM address-lookup caching hides repeat lookups from it, which
 * defeats the logging goal, so this is set explicitly rather than left to JVM
 * defaults, which differ under native-image. Latency cost against a LAN resolver
 * is negligible, and the resolver's own result cache prevents most repeat lookups
 * anyway.
 *
 * <p>The other half of "DNS must go through AdGuard" — forcing the OS resolver
 * instead of Vert.x's async Netty one via {@code -Dvertx.disableDnsResolver=true} —
 * has to be a JVM system property set before Vert.x initializes (Vert.x reads it
 * via {@code System.getProperty} during init, before any Quarkus config is read),
 * so it belongs in the container {@code CMD}, not here — see the Dockerfiles.
 */
@ApplicationScoped
public class NetworkConfig {

    void onStart(@Observes StartupEvent event) {
        Security.setProperty("networkaddress.cache.ttl", "5");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }
}
