package com.snigji.unshortener.resolver;

import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

public class WebClientProducer {

    @Produces
    @ApplicationScoped
    WebClient webClient(Vertx vertx) {
        WebClientOptions options = new WebClientOptions()
                // Walk manually, one hop at a time — non-negotiable. Auto-follow destroys
                // per-hop inspection and accurate chain reporting.
                .setFollowRedirects(false)
                // UA is set per-request from the resolved UaProfile, not client-wide.
                .setUserAgentEnabled(false)
                .setConnectTimeout(2000)
                .setMaxPoolSize(64);
        return WebClient.create(vertx, options);
    }
}
