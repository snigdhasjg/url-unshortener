package com.snigji.unshortener.rest;

import com.snigji.unshortener.resolver.ResolverLimits;
import com.snigji.unshortener.service.ResolverService;
import com.snigji.unshortener.service.UnshortenMapper;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.net.URI;
import java.util.Optional;

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
    public Uni<Response> unshorten(@QueryParam("url") @NotNull URI url) {
        return resolverService.resolve(url, Optional.of("android"))
                .ifNoItem().after(ResolverLimits.API_CEILING)
                .recoverWithItem(() -> resolverService.hardCutoffFallback(url))
                .map(result -> Response.ok(mapper.toCompat(result, url.toString())).build());
    }

    // Always 200 here, even on malformed input beyond a missing url param. A present
    // but empty "?url=" is treated as absent by RESTEasy's query-param extraction
    // (never reaches UrlParamConverterProvider at all — verified empirically), so it
    // fails @NotNull the same way a genuinely missing param does, and never reaches
    // this mapper. url can't be blank here: the only way to reach BadRequestException
    // is via a value that made it to the converter, which is by construction non-empty.
    @ServerExceptionMapper
    public Response mapBadRequest(BadRequestException e, UriInfo uriInfo) {
        String url = uriInfo.getQueryParameters().getFirst("url");
        return Response.ok(UnshortenResponse.failure(url, e.getMessage())).build();
    }
}
