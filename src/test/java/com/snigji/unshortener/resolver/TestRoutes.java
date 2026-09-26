package com.snigji.unshortener.resolver;

import io.vertx.core.http.HttpServerRequest;

/** Minimal raw-Vert.x routes backing {@link RedirectResolverTest} — no vertx-web needed on the server side. */
final class TestRoutes {

    private TestRoutes() {
    }

    static void handle(HttpServerRequest req) {
        String path = req.path();
        switch (path) {
            case "/start" -> redirect(req, "/middle");
            case "/middle" -> redirect(req, "/final");
            case "/final" -> html(req, "<html>done</html>");
            case "/loop-a" -> redirect(req, "/loop-b");
            case "/loop-b" -> redirect(req, "/loop-a");
            case "/head-405" -> {
                if ("HEAD".equalsIgnoreCase(req.method().name())) {
                    req.response().setStatusCode(405).end();
                } else {
                    html(req, "<html>ok</html>");
                }
            }
            case "/head-404" -> {
                if ("HEAD".equalsIgnoreCase(req.method().name())) {
                    req.response().setStatusCode(404).end();
                } else {
                    redirect(req, "/final");
                }
            }
            case "/head-hangs" -> {
                if ("HEAD".equalsIgnoreCase(req.method().name())) {
                    // Never call end() — simulates a server that accepts the connection but
                    // black-holes HEAD entirely (the dl.flipkart.com /s/ case). The client-side
                    // request timeout is what eventually gives up on this, not the server.
                } else {
                    redirect(req, "/final");
                }
            }
            case "/head-slow-but-ok" -> {
                if ("HEAD".equalsIgnoreCase(req.method().name())) {
                    io.vertx.core.Vertx.currentContext().owner()
                            .setTimer(200, id -> redirect(req, "/final"));
                } else {
                    redirect(req, "/final");
                }
            }
            case "/meta-refresh" -> html(req,
                    "<html><head><meta http-equiv=\"refresh\" content=\"0;url=/final\"></head></html>");
            case "/js-redirect" -> html(req, "<html><script>window.location = buildUrl();</script></html>");
            case "/intent-redirect" -> redirect(req,
                    "intent://scan/#Intent;scheme=https;package=com.example;"
                            + "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end");
            case "/cookie1" -> {
                req.response().putHeader("Set-Cookie", "session=abc123");
                redirect(req, "/cookie2");
            }
            case "/cookie2" -> {
                String cookie = req.getHeader("Cookie");
                if (cookie != null && cookie.contains("session=abc123")) {
                    redirect(req, "/cookie-final");
                } else {
                    html(req, "<html>no-cookie</html>");
                }
            }
            case "/cookie-final" -> html(req, "<html>done</html>");
            default -> {
                if (path.matches("^/chain/\\d+$")) {
                    int n = Integer.parseInt(path.substring("/chain/".length()));
                    redirect(req, "/chain/" + (n + 1));
                } else {
                    req.response().setStatusCode(404).end();
                }
            }
        }
    }

    private static void redirect(HttpServerRequest req, String location) {
        req.response().setStatusCode(302).putHeader("Location", location).end();
    }

    private static void html(HttpServerRequest req, String body) {
        // Vert.x suppresses the entity body on a HEAD response automatically, so this is
        // correct for both methods; the resolver never inspects a HEAD response's body anyway.
        req.response().setStatusCode(200).putHeader("Content-Type", "text/html").end(body);
    }
}
