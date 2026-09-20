package com.snigji.unshortener.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Test-only resource that always throws, to exercise {@link UnexpectedExceptionMapper}. */
@Path("/test-only/boom")
public class BoomResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String boom() {
        throw new IllegalStateException("boom");
    }
}
