package com.snigji.unshortener.rest;

import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.resolver.ResolverLimits;
import com.snigji.unshortener.service.ResolverService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

@Path("/api/v1/resolve")
public class ResolveResource {

    @Inject
    ResolverService resolverService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> resolve(@QueryParam("url") String url, @QueryParam("profile") String profile) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("missing url parameter");
        }
        return resolverService.resolve(url, Optional.ofNullable(profile))
                .ifNoItem()
                .after(ResolverLimits.API_CEILING)
                .recoverWithItem(() -> resolverService.hardCutoffFallback(url))
                .map(this::toResponse);
    }

    private Response toResponse(Result result) {
        long cacheSeconds = Math.max(0, result.budgetRemainingMs() / 1000);
        return Response.ok(result)
                .header("Cache-Control", "max-age=" + cacheSeconds)
                .build();
    }
}
