package com.snigji.unshortener.rest;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Global backstop for anything the resource-level {@code @ServerExceptionMapper}s
 * don't handle, so a bug returns the same {@code {"error": ...}} shape as documented
 * failures instead of Quarkus's default error page.
 */
@Provider
public class UnexpectedExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnexpectedExceptionMapper.class);

    @Override
    public Response toResponse(Throwable t) {
        if (t instanceof WebApplicationException wae) {
            return mapWebApplicationException(wae);
        }
        LOG.error("unhandled exception", t);
        return Response.serverError().entity(Map.of("error", "internal")).build();
    }

    /**
     * A {@code BadRequestException(message)}-style exception carries its message on
     * the exception itself, not in its {@code Response} entity — {@code new
     * BadRequestException("x").getResponse()} has status 400 but an empty body. Rebuild
     * the entity from the exception so callers get the {@code {"error": ...}} shape
     * consistently. An exception that already set its own entity (e.g. a future
     * `WebApplicationException` built with a populated `Response`) is passed through
     * untouched — this only fills in the gap for the message-only constructors.
     */
    private Response mapWebApplicationException(WebApplicationException wae) {
        Response response = wae.getResponse();
        if (response.hasEntity()) {
            return response;
        }
        // Routing outcomes (404, 405) reach here too, via the same message-only
        // constructors — no separate passthrough needed, since we're rebuilding the
        // same status either way and just adding a body Quarkus previously left empty.
        String message = Objects.requireNonNullElse(wae.getMessage(), response.getStatusInfo().getReasonPhrase());
        return Response.status(response.getStatus()).entity(Map.of("error", message)).build();
    }
}
