package com.snigji.unshortener.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/healthz")
public class HealthzResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response healthz() {
        return Response.ok(Map.of("status", "ok")).build();
    }
}
