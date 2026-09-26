# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`unshortener` is a self-hosted URL-unshortener API (Quarkus, Java 25, group `com.snigji`). Given a shortened URL it walks the redirect chain and reports the final destination plus the hops taken, under a hard 5-second response ceiling. Full design intent, rationale, and constraints live in `plan.md` at the repo root — read it before making non-trivial changes to the resolver, caching, or API contract; this file only covers what a contributor needs to start working.

## Commands

Build and run:
```shell
./gradlew quarkusDev          # dev mode with live reload; Dev UI at http://localhost:8080/q/dev/
./gradlew build                # compile, test, and package -> build/quarkus-app/quarkus-run.jar
./gradlew test                 # run tests
./gradlew test --tests "com.snigji.unshortener.resolver.UrlNormalizerTest"   # single test class
```

Run the packaged app:
```shell
java -jar build/quarkus-app/quarkus-run.jar
```

Über-jar build:
```shell
./gradlew build -Dquarkus.package.jar.type=uber-jar
java -jar build/*-runner.jar
```

Docker images (build the jar with `./gradlew build` first): `src/main/docker/Dockerfile.jvm` is the only variant — native-image support was dropped (see "Not yet done") and the `legacy-jar` Dockerfile was unused Quarkus scaffolding, never built or referenced anywhere in the repo, so it was removed too. It sets `-Dvertx.disableDnsResolver=true` at the JVM level, not in `application.yml` — Vert.x reads that flag via `System.getProperty` during init, before Quarkus config is loaded. It also defaults to ports 80/443 (`QUARKUS_HTTP_PORT`/`QUARKUS_HTTP_SSL_PORT`, set as `ENV`, not `-D` — sysprops outrank env vars in SmallRye Config, which would otherwise make a `docker run -e QUARKUS_HTTP_...` override silently no-op). HTTPS is opt-in at run time: mount a PEM cert+key and set `QUARKUS_HTTP_SSL_CERTIFICATE_FILES`/`QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES`; both are required together or Quarkus fails at startup (loud failure, not a silent fallback to plaintext). The private CA is deliberately not read by the container — fold any intermediate chain into the cert file yourself. `quarkus.http.insecure-requests=enabled` in `application.yml` keeps port 80 from ever being redirected to 443.

## Build system

Gradle (Kotlin DSL) with the Quarkus Gradle plugin (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`). Quarkus platform/plugin versions are pinned in `gradle.properties` (currently 3.39.4) and should be updated there, not per-dependency. Java 25, with `-parameters` enabled at compile time (needed for Quarkus JAX-RS/CDI parameter reflection).

Extensions: `quarkus-rest-jackson`, `quarkus-smallrye-health`, `quarkus-cache`, `quarkus-vertx`, `quarkus-arc`, `quarkus-hibernate-validator`. Outbound HTTP is the **Vert.x WebClient** (`smallrye-mutiny-vertx-web-client`) — deliberately not a Quarkus extension; `quarkus-vertx` only supplies the managed `Vertx` instance. Import the Mutiny variant (`io.vertx.mutiny.ext.web.client.*`, including `io.vertx.mutiny.core.buffer.Buffer` — not the plain `io.vertx.core.buffer.Buffer`, which won't type-check against a Mutiny `HttpRequest`/`HttpResponse`).

## Architecture

One resolver, one cache, one code path, two projections of it:

- **`domain/`** — the wire model. `Destination` is a sealed interface (`Web`/`AppIntent`/`Store`/`Unresolved`) serialized by Jackson via runtime type, no discriminator. `Status`/`StopReason`/`HopVia` are enums with `@JsonValue` for their snake_case wire form. `quarkus.jackson.property-naming-strategy=SNAKE_CASE` (application.yml) handles record field names; it does not affect enum constants, hence the explicit `@JsonValue`.
- **`resolver/`** — `RedirectResolver` recursively walks one hop at a time (`WebClient.followRedirects(false)` — never auto-follow), carrying one absolute deadline in `WalkState` (`ResolverLimits`: 4800ms resolver budget, 1500ms per-hop cap, 400ms floor, 10 max hops, 64KB body cap). `UrlNormalizer`, `MetaRefreshScanner`, `JsRedirectDetector`, and `IntentUrlParser` are pure/static and unit-tested without any server — though `UrlNormalizer`, like `RedirectResolver`, now throws `jakarta.ws.rs.BadRequestException` directly (so both a JAX-RS `ParamConverterProvider` and callers deeper in the stack can propagate it unchanged), which couples it to JAX-RS even though it needs no running server to test. `CookieJar` is per-`WalkState` (never shared) and scopes by the `Set-Cookie` `Domain` attribute only — not full RFC 6265.
- **`cache/`** — `WholeWalkCache` (L1, whole result) and `EdgeCache` (L2, per-hop, makes partial results resumable) are each a pair of plain Caffeine caches (success: 1d, failure: 5min), not `@CacheResult` — Quarkus's Caffeine config only supports one static TTL per named cache, and `@CacheResult` doesn't cache exceptions anyway (the resolver never throws on failure; a failure is always a normal `Result`). A hop whose response carried `Set-Cookie` is marked non-cacheable and never enters `EdgeCache` — replaying it from cache would silently skip that cookie on a later walk. On an `EdgeCache` hit, the cached hop's `via` field is overridden with the current walk's `via` — it's a walk-relative fact, not a property of the target URL (this was a real bug caught by cross-test cache reuse; see `RedirectResolverTest`).
- **`ua/`** — named UA profiles via `@ConfigMapping` (`UaProfilesConfig`), not hardcoded strings. The `Map<String, Profile>` field needs `@WithParentName` to flatten under the `ua-profiles` prefix instead of nesting under the accessor's own name — easy to get wrong, cost a debugging round-trip once already.
- **`security/`** — `Guard` interface (`checkUrl`/`checkIp`) is a deliberate stub (`AllowAllGuard`); this service fetches attacker-controlled URLs from inside a home LAN, so the hook must stay structural even though there's no real policy yet. `checkIp` is never actually called with a real address — the WebClient resolves-and-connects in one step, so pinning the IP between DNS check and connect needs a custom address resolver, not implemented.
- **`service/`** — `ResolverService` is the shared facade: normalizes input, checks the L1 cache, calls `RedirectResolver`, and provides `hardCutoffFallback` for the resource-level defense-in-depth timeout (should never fire; logs at WARN if it does, since it means the resolver's own deadline arithmetic has a bug). `UnshortenMapper` projects a `Result` onto the compat shape — deep-link-aware (`AppIntent.fallback` → Play Store URL for `Store` → raw scheme, in that order).
- **`rest/`** — `ResolveResource` (`/api/v1/resolve`, rich) and `UnshortenResource` (`/api/v2/unshorten`, unshorten.me-compatible: same path as theirs, versioned independently from ours on purpose, profile hardcoded to `android`, malformed input still returns 200 with `success:false` rather than 400) both call `ResolverService` only — never duplicate resolution logic in a resource. Health checks are `quarkus-smallrye-health`'s standard `/q/health` — no bespoke `/healthz`, since there's nothing bespoke to report yet (no registered `HealthCheck` beans).

`Status.from(stopReason, anyHopCompleted)` is the one place that decides `resolved`/`partial`/`failed`; a transport/DNS error is `failed` only if it happened before any earlier hop succeeded, otherwise `partial` with the last good hop as `final_url` — this reading favors the rich API's own definition ("failed = couldn't complete even the first hop") over a stricter blanket rule the compat-endpoint section of `plan.md` could also support. If you touch this, re-read that section of the plan first.

## Not yet done

Tracked as gaps rather than silently missing: `Hop.remoteIp` is always `null` (Vert.x's `WebClient` response doesn't expose the underlying connection without dropping to raw `HttpClient`); gzip-bomb protection is a `Range` header plus post-fetch truncation, not a true streaming cap. Native-image support was dropped on purpose: this is a long-running server (not a CLI or FaaS workload), so JDK mode is the recommended way to run it, and the reflection-config upkeep (`NativeReflectionConfig`, `quarkus.native.enable-https-url-handler`) wasn't worth paying for a startup-time/RSS win this workload doesn't need — the CI workflow, Dockerfiles, and `application.yml` no longer reference native-image at all. Still open: DNS-resolver-disable verified via AdGuard logs — best done on the target homelab hardware. Metrics (`micrometer-registry-prometheus`) intentionally omitted per `plan.md`, which marks it optional. Container supply-chain attestation is likewise deliberately minimal: CI keeps BuildKit's `provenance: true` (written into the image index, no Sigstore signing) but sets `sbom: false` and publishes no `actions/attest` attestation — the SBOM scan across two architectures and the Sigstore/attestation upkeep aren't worth paying for on a single-user homelab service. The build job's `id-token`/`attestations` permissions were dropped along with it, so re-enabling attestation means restoring those too. Image tags: PR builds push `ghcr.io/<repo>:commit-<short-sha>`; pushes to `main` push the semver version plus `latest`.
