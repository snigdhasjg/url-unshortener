package com.snigji.unshortener.rest;

import com.snigji.unshortener.resolver.ResolverLimits;
import com.snigji.unshortener.service.ResolverService;
import com.snigji.unshortener.service.UnshortenMapper;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * unshorten.me-compatible endpoint. Path is byte-identical to theirs on purpose —
 * an existing client only changes the hostname. Ours versions independently under
 * {@code /api/v1/resolve}; don't "fix" the version mismatch to look consistent.
 *
 * <p>Profile is hardcoded to android: the {@code profile} query param isn't even
 * read here, so it's ignored by construction. An {@code Authorization} header, if
 * sent, is likewise never inspected — JAX-RS doesn't reject unrecognized headers,
 * so silently accepting it needs no code either.
 */
@Path("/api/v2/unshorten")
public class UnshortenResource {

    @Inject
    ResolverService resolverService;

    @Inject
    UnshortenMapper mapper;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> unshorten(@QueryParam("url") String url) {
        if (url == null || url.isBlank()) {
            // Only case that isn't a plain 200 — compat clients won't handle 422.
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("error", "missing url parameter")).build());
        }
        return resolverService.resolve(url, java.util.Optional.of("android"))
                .ifNoItem().after(ResolverLimits.API_CEILING)
                .recoverWithItem(() -> resolverService.hardCutoffFallback(url))
                .map(result -> Response.ok(mapper.toCompat(result, url)).build());
    }

    // Always 200 here, even on malformed input beyond a missing url param. url is
    // non-null: the missing-param guard above returns before a BadRequestException
    // can be thrown, so this only ever fires for malformed-but-present input.
    @ServerExceptionMapper
    public Response mapBadRequest(BadRequestException e, UriInfo uriInfo) {
        String url = uriInfo.getQueryParameters().getFirst("url");
        return Response.ok(UnshortenResponse.failure(url, e.getMessage())).build();
    }
}
