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

import java.util.Map;
import java.util.Optional;

@Path("/api/v1/resolve")
public class ResolveResource {

    @Inject
    ResolverService resolverService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> resolve(@QueryParam("url") String url, @QueryParam("profile") String profile) {
        if (url == null || url.isBlank()) {
            return Uni.createFrom().item(badRequest("missing url parameter"));
        }
        try {
            // Resource-level hard cutoff: defense in depth alongside the resolver's own
            // deadline and the per-request Vert.x timeout. A timeout that produced hops is
            // a successful partial, never a gateway failure — recoverWithItem, not failWith.
            return resolverService.resolve(url, Optional.ofNullable(profile))
                    .ifNoItem().after(ResolverLimits.API_CEILING)
                    .recoverWithItem(() -> resolverService.hardCutoffFallback(url))
                    .map(this::toResponse);
        } catch (BadRequestException e) {
            return Uni.createFrom().item(badRequest(e.getMessage()));
        }
    }

    private Response toResponse(Result result) {
        long cacheSeconds = Math.max(0, result.budgetRemainingMs() / 1000);
        return Response.ok(result)
                .header("Cache-Control", "max-age=" + cacheSeconds)
                .build();
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
    }
}
