package com.snigji.unshortener.resolver;

import com.snigji.unshortener.cache.EdgeCache;
import com.snigji.unshortener.domain.Destination;
import com.snigji.unshortener.domain.HopVia;
import com.snigji.unshortener.domain.Result;
import com.snigji.unshortener.domain.Status;
import com.snigji.unshortener.domain.StopReason;
import com.snigji.unshortener.security.AllowAllGuard;
import com.snigji.unshortener.ua.UaProfile;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the resolver end-to-end against a raw Vert.x HTTP server (no Quarkus
 * REST server, no CDI) — "unit-testable with no server" per the build plan means
 * no *application* server, not that redirect walking can be tested without any
 * HTTP endpoint to walk against at all.
 */
class RedirectResolverTest {

    private static io.vertx.core.Vertx coreVertx;
    private static io.vertx.mutiny.core.Vertx mutinyVertx;
    private static HttpServer server;
    private static int port;
    private static RedirectResolver resolver;

    private static final UaProfile PROFILE = new UaProfile("android", List.of(Map.entry("User-Agent", "test-agent")));

    @BeforeAll
    static void startServer() throws Exception {
        coreVertx = io.vertx.core.Vertx.vertx();
        mutinyVertx = io.vertx.mutiny.core.Vertx.newInstance(coreVertx);

        CompletableFuture<Void> started = new CompletableFuture<>();
        server = coreVertx.createHttpServer().requestHandler(TestRoutes::handle);
        server.listen(0, ar -> {
            if (ar.succeeded()) {
                port = ar.result().actualPort();
                started.complete(null);
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        started.get(10, TimeUnit.SECONDS);

        WebClient webClient = WebClient.create(mutinyVertx,
                new WebClientOptions().setFollowRedirects(false).setUserAgentEnabled(false).setConnectTimeout(2000));

        resolver = new RedirectResolver();
        resolver.webClient = webClient;
        resolver.guard = new AllowAllGuard();
        resolver.edgeCache = new EdgeCache();
    }

    @AfterAll
    static void stopServer() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        server.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
        coreVertx.close();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** RedirectResolver.resolve now takes an already-normalized URI, not a raw string. */
    private URI uri(String path) {
        return URI.create(url(path));
    }

    private Result await(io.smallrye.mutiny.Uni<Result> uni) {
        return uni.await().atMost(java.time.Duration.ofSeconds(10));
    }

    @Test
    void followsAPlainRedirectChainToATerminal200() {
        Result result = await(resolver.resolve(uri("/start"), PROFILE));
        assertEquals(Status.RESOLVED, result.status());
        assertEquals(StopReason.TERMINAL_RESPONSE, result.stopReason());
        assertEquals(url("/final"), result.finalUrl());
        assertEquals(3, result.hops().size());
        assertEquals(HopVia.INITIAL, result.hops().get(0).via());
        assertEquals(HopVia.HTTP, result.hops().get(1).via());
    }

    @Test
    void detectsALoop() {
        Result result = await(resolver.resolve(uri("/loop-a"), PROFILE));
        assertEquals(Status.PARTIAL, result.status());
        assertEquals(StopReason.LOOP, result.stopReason());
    }

    @Test
    void stopsAtMaxHops() {
        Result result = await(resolver.resolve(uri("/chain/0"), PROFILE));
        assertEquals(Status.PARTIAL, result.status());
        assertEquals(StopReason.MAX_HOPS, result.stopReason());
        assertEquals(ResolverLimits.MAX_HOPS, result.hops().size());
    }

    @Test
    void fallsBackToGetWhenHeadIsRejected() {
        Result result = await(resolver.resolve(uri("/head-405"), PROFILE));
        assertEquals(Status.RESOLVED, result.status());
        assertEquals("GET", result.hops().get(0).method());
    }

    @Test
    void fallsBackToGetWhenHeadIsSpuriouslyNotFound() {
        Result result = await(resolver.resolve(uri("/head-404"), PROFILE));
        assertEquals(Status.RESOLVED, result.status());
        assertEquals("GET", result.hops().get(0).method());
        assertEquals(url("/final"), result.finalUrl());
    }

    @Test
    void followsMetaRefresh() {
        Result result = await(resolver.resolve(uri("/meta-refresh"), PROFILE));
        assertEquals(Status.RESOLVED, result.status());
        assertEquals(url("/final"), result.finalUrl());
        assertTrue(result.hops().stream().anyMatch(h -> h.via() == HopVia.META_REFRESH));
    }

    @Test
    void flagsSuspectedJsRedirectAsPartial() {
        Result result = await(resolver.resolve(uri("/js-redirect"), PROFILE));
        assertEquals(Status.PARTIAL, result.status());
        assertEquals(StopReason.JS_SUSPECTED, result.stopReason());
        assertEquals(url("/js-redirect"), result.finalUrl());
    }

    @Test
    void resolvesIntentSchemeAsTerminalSuccessNotError() {
        Result result = await(resolver.resolve(uri("/intent-redirect"), PROFILE));
        assertEquals(Status.RESOLVED, result.status());
        assertEquals(StopReason.NON_HTTP_SCHEME, result.stopReason());
        Destination.AppIntent appIntent = assertInstanceOf(Destination.AppIntent.class, result.destination());
        assertEquals("https://example.com/fallback", appIntent.fallback().toString());
    }

    @Test
    void carriesCookiesFromOneHopToTheNext() {
        Result result = await(resolver.resolve(uri("/cookie1"), PROFILE));
        assertEquals(Status.RESOLVED, result.status());
        assertEquals(url("/cookie-final"), result.finalUrl());
    }

    @Test
    void classifiesConnectionFailureAsTransportError() {
        Result result = await(resolver.resolve(URI.create("http://127.0.0.1:1/unreachable"), PROFILE));
        assertEquals(Status.FAILED, result.status());
        assertEquals(StopReason.TRANSPORT_ERROR, result.stopReason());
        assertInstanceOf(Destination.Unresolved.class, result.destination());
    }
}
