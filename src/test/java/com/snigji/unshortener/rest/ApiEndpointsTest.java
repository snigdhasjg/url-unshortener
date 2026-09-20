package com.snigji.unshortener.rest;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Full-stack tests through the real Quarkus HTTP server. The redirect-target
 * server is a plain Vert.x {@code HttpServer} (no vertx-web needed) so these
 * don't depend on any external network access.
 */
@QuarkusTest
class ApiEndpointsTest {

    private static io.vertx.core.Vertx targetVertx;
    private static io.vertx.core.http.HttpServer targetServer;
    private static int targetPort;

    @BeforeAll
    static void startTargetServer() throws Exception {
        targetVertx = io.vertx.core.Vertx.vertx();
        CompletableFuture<Void> started = new CompletableFuture<>();
        targetServer = targetVertx.createHttpServer().requestHandler(req -> {
            if ("/start".equals(req.path())) {
                req.response().setStatusCode(302).putHeader("Location", "/final").end();
            } else if ("/final".equals(req.path())) {
                req.response().setStatusCode(200).putHeader("Content-Type", "text/html").end("<html>done</html>");
            } else {
                req.response().setStatusCode(404).end();
            }
        });
        targetServer.listen(0, ar -> {
            if (ar.succeeded()) {
                targetPort = ar.result().actualPort();
                started.complete(null);
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        started.get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopTargetServer() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        targetServer.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
        targetVertx.close();
    }

    private String targetUrl(String path) {
        return "http://localhost:" + targetPort + path;
    }

    @Test
    void healthReportsUp() {
        RestAssured.given()
                .when().get("/q/health")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void resolveRejectsMissingUrl() {
        RestAssured.given()
                .when().get("/api/v1/resolve")
                .then().statusCode(400);
    }

    @Test
    void resolveRejectsMalformedInputWith400() {
        RestAssured.given()
                .queryParam("url", "ftp://example.com/x")
                .when().get("/api/v1/resolve")
                .then().statusCode(400)
                .body("error", equalTo("unsupported input scheme: ftp"));
    }

    @Test
    void resolveFollowsARedirectChain() {
        RestAssured.given()
                .queryParam("url", targetUrl("/start"))
                .when().get("/api/v1/resolve")
                .then().statusCode(200)
                .body("status", equalTo("resolved"))
                .body("stop_reason", equalTo("terminal_response"))
                .body("final_url", equalTo(targetUrl("/final")))
                .body("hops", notNullValue());
    }

    @Test
    void unshortenRejectsMissingUrlWith400() {
        RestAssured.given()
                .when().get("/api/v2/unshorten")
                .then().statusCode(400);
    }

    @Test
    void unshortenFollowsARedirectChain() {
        RestAssured.given()
                .queryParam("url", targetUrl("/start"))
                .when().get("/api/v2/unshorten")
                .then().statusCode(200)
                .body("success", equalTo(true))
                .body("shortened_url", equalTo(targetUrl("/start")))
                .body("unshortened_url", equalTo(targetUrl("/final")));
    }

    @Test
    void unshortenReturns200WithSuccessFalseOnMalformedInput() {
        // v2 is unshorten.me-compatible: only a *missing* url param is 400 — a malformed
        // one still comes back 200 with success:false, since compat clients won't handle 422.
        RestAssured.given()
                .queryParam("url", "ftp://example.com/x")
                .when().get("/api/v2/unshorten")
                .then().statusCode(200)
                .body("success", equalTo(false))
                .body("shortened_url", equalTo("ftp://example.com/x"))
                .body("unshortened_url", equalTo("ftp://example.com/x"));
    }

    @Test
    void unshortenIgnoresProfileParamAndAuthorizationHeader() {
        RestAssured.given()
                .queryParam("url", targetUrl("/start"))
                .queryParam("profile", "desktop")
                .header("Authorization", "Token abc123")
                .when().get("/api/v2/unshorten")
                .then().statusCode(200)
                .body("success", equalTo(true));
    }

    @Test
    void unmatchedPathStillReturns404() {
        RestAssured.given()
                .when().get("/api/v1/nonexistent")
                .then().statusCode(404);
    }

    @Test
    void resolveRejectsUnknownProfileWith400() {
        RestAssured.given()
                .queryParam("url", targetUrl("/start"))
                .queryParam("profile", "bogus")
                .when().get("/api/v1/resolve")
                .then().statusCode(400)
                .body("error", equalTo("unknown profile: bogus"));
    }

    @Test
    void unexpectedExceptionReturnsErrorShape() {
        RestAssured.given()
                .when().get("/test-only/boom")
                .then().statusCode(500)
                .body("error", equalTo("internal"));
    }
}
