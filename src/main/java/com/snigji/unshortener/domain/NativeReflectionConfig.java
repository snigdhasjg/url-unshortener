package com.snigji.unshortener.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.snigji.unshortener.rest.UnshortenResponse;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection glue, kept in one place rather than scattered across the
 * wire model. Two unrelated gaps, both only visible in native builds:
 *
 * <p>1. {@code quarkus.jackson.property-naming-strategy=SNAKE_CASE} resolves this
 * Jackson class by name via reflection at runtime (JacksonRecorder), but the
 * quarkus-jackson extension never registers it for reflection itself —
 * {@code ClassNotFoundException} on every JSON response without it.
 *
 * <p>2. {@code ResolveResource}/{@code UnshortenResource} return {@code Uni<Response>}
 * (needed for per-response {@code Cache-Control} headers), not a concretely-typed
 * body. Quarkus's build-time reflection-free Jackson serializers are generated from a
 * resource method's declared return type, so returning the erased {@code Response}
 * means none of these get one — Jackson falls back to plain reflection at runtime and
 * finds nothing registered, failing with "No serializer found ... and no properties
 * discovered" for whichever type it hits first. Every type reachable from a JSON
 * response body needs to be listed here.
 */
@RegisterForReflection(targets = {
        PropertyNamingStrategies.SnakeCaseStrategy.class,
        Result.class,
        Hop.class,
        Destination.class,
        Destination.Web.class,
        Destination.AppIntent.class,
        Destination.Store.class,
        Destination.Unresolved.class,
        Status.class,
        StopReason.class,
        HopVia.class,
        UnshortenResponse.class
})
public class NativeReflectionConfig {
}
