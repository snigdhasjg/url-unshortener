# urlx — URL Unshortener API

Self-hosted URL unshortener. Given a shortened URL, walk the redirect chain and
report the final destination plus the full path taken.

Reference behaviour: `https://unshorten.me/api` (`GET /api/v2/unshorten?url=...`).

## Project state — ALREADY SCAFFOLDED

The project exists. Do not re-run `quarkus create app`. `src/` is empty
(`--no-code`), so all application code is still to be written.

```
groupId:artifactId   com.snigji:unshortener:1.0.0-SNAPSHOT
package              com.snigji.unshortener
build                Gradle, Kotlin DSL (build.gradle.kts)
java                 25
extensions           rest-jackson, vertx, cache, smallrye-health
```

## Runtime & deployment

- Java 25 (Temurin), Quarkus latest (3.39.x), native image via Mandrel 25
- Dev: `quarkus dev` (or `./gradlew quarkusDev`) with live reload on the JVM
- Native: `quarkus build --native -Dquarkus.native.container-build=true`
  (CI or a machine with 8GB free). Prefer the CLI over raw Gradle — the underlying
  properties changed in Quarkus 3.9 (`quarkus.package.type=native` is gone, replaced by
  `quarkus.native.enabled` + `quarkus.package.jar.enabled=false`) and the CLI sets them
  correctly.
- Keep the JVM-mode image buildable as a fallback if native-image fights a dependency
- Target: ~60-80MB image, ~50MB RSS
- Homelab Docker, IPvlan L3 (container has its own LAN IP)
- DNS goes through AdGuard, which has this container allowlisted (unfiltered but logged)

Known rough edge: the Quarkus Gradle plugin doesn't fully support Gradle's
configuration cache. On odd build failures, try `--no-configuration-cache` first.

## Dependencies

Already present via `quarkus create`:

```
quarkus-rest-jackson      (pulls quarkus-rest transitively)
quarkus-vertx
quarkus-cache
quarkus-smallrye-health
```

### One dependency must be added by hand

The Vert.x WebClient is **not a Quarkus extension**. `quarkus ext add` will not find it.
`quarkus-vertx` provides only the managed Vert.x instance. Add to `build.gradle.kts`:

```kotlin
// Vert.x WebClient — deliberately not a Quarkus extension.
// quarkus-vertx supplies the Vertx instance only. Version is managed by the
// Quarkus platform BOM, so versionless is correct. Do not "fix" either of these.
implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")
```

Import the Mutiny variant: `io.vertx.mutiny.ext.web.client.WebClient`.
NOT the bare `io.vertx.ext.web.client.WebClient`.

Optional, if metrics are wanted: `quarkus ext add micrometer-registry-prometheus`.

Do NOT add `quarkus-rest-client-jackson`. The REST Client is built for typed calls
against a known API; this service walks arbitrary URLs and inspects raw headers.

Outbound HTTP: **Vert.x WebClient**, reactive/Mutiny throughout — recursive `Uni`,
not a blocking loop.

---

## Hard constraints

| Constraint | Value |
|---|---|
| API response time | **5000ms hard ceiling** |
| Resolver budget | 4800ms |
| Response reserve | 200ms |
| Per-hop cap | `min(1500ms, remaining)` |
| Floor to attempt another hop | 400ms |
| Max hops | 10 |
| Max body read | 64KB |

**Deadline propagation, not fixed per-hop timeouts.** Compute one absolute deadline at
request entry, carry it in `WalkState`, give each hop whatever remains capped at 1500ms.
Below the floor, stop and return partial.

Defence in depth: deadline in `WalkState`, per-request Vert.x timeout, and a hard cutoff
at the resource level so a bug in budget arithmetic can't breach 5s.

---

## Core resolution logic

### Redirect walking

- `webClient.followRedirects(false)` — walk manually, one hop at a time. Non-negotiable;
  auto-follow destroys per-hop inspection and accurate chain reporting.
- Try `HEAD` first, fall back to `GET` on 405/501/400/403. HEAD is cheaper and often
  avoids incrementing the shortener's click counter or burning a single-use link.
- `GET` requests send `Range: bytes=0-65535` and cap the read regardless.
- Cap decompressed body size (gzip bombs).
- Loop detection via a visited set of normalized URLs.
- Handle relative `Location`, unencoded characters, missing schemes.
- Per-resolution cookie jar (some shorteners set on hop 1, require on hop 2). Never shared.
- Rebuild headers from scratch each hop — nothing leaks across a host boundary.

### Redirect statuses

301, 302, 303, 307, 308. A redirect status with an empty `Location` is terminal.

### Meta refresh — IN SCOPE

`<meta http-equiv="refresh" content="0;url=...">`. Needs a GET and an HTML scan of the
first 64KB. Handle: comments, quoted/unquoted attribute values, arbitrary attribute
order, entity-escaped URLs (`&amp;`), `url=` case-insensitive, single and double quotes.

### JavaScript redirects — DETECT, DO NOT EXECUTE

No headless browser. A cold Chromium render is 2-6s, which breaks the 5s ceiling outright.

On a terminal 200 HTML response, statically scan for redirect intent:
- `window.location` / `location.href =` / `location.replace(`
- a single auto-submitting form
- a `<noscript>` block containing a link

If found, extract the URL by regex where possible and set `stop_reason: js_suspected`.
This makes the API honest about what it couldn't finish, and the counter tells us later
whether a browser tier would ever be worth building.

### Non-HTTP schemes = terminal SUCCESS, not error

Android-targeted links frequently end in:
- `intent://...#Intent;scheme=https;package=com.example;end`
- `market://details?id=...`
- `https://play.google.com/store/apps/details?id=...`

Parse `intent://` URLs to extract the embedded `S.browser_fallback_url`.

Getting this wrong means the most interesting Android resolutions report as failures.

---

## Domain model

Sealed interface + records. This is why we're in Java — exhaustive pattern matching over
terminal outcomes, compiler-checked.

```java
sealed interface Destination {
    record Web(URI uri)                      implements Destination;
    record AppIntent(URI raw, URI fallback)  implements Destination;  // intent://
    record Store(String packageId)           implements Destination;  // market://
    record Unresolved(Reason reason)         implements Destination;
}
```

`Hop` and `Result` as records — serialize to JSON with no boilerplate. Quarkus 3.37+
has reflection-free Jackson serializers on by default, so native-image reflection
registration is mostly unnecessary.

---

## API contract — two endpoints, one engine

```
GET /api/v1/resolve?url=<encoded>&profile=android     # rich
GET /api/v2/unshorten?url=<encoded>                   # unshorten.me-compatible
GET /healthz
```

**The two endpoints share one resolver, one cache, one code path.** The compat endpoint
is a *projection* of the same `Result` object, not a second implementation. Two
serializers over one engine. Do not fork the resolution logic.

On the naming: `v2` is **unshorten.me's** version, not ours. The path is byte-identical
to theirs so an existing client only changes the hostname. Ours versions independently
under `/api/v1/resolve`. Don't "fix" this to look consistent — the mismatch is the point.

---

## Endpoint 1 — `/api/v1/resolve` (rich)

### Response

```jsonc
{
  "original_url": "...",
  "final_url": "...",              // always populated, even on partial
  "destination": { },              // the sealed type
  "status": "resolved|partial|failed",
  "stop_reason": "...",
  "hops": [
    { "url": "...", "method": "HEAD", "status_code": 301,
      "location": "...", "via": "initial|http|meta-refresh",
      "remote_ip": "...", "content_type": "...", "elapsed_ms": 120 }
  ],
  "elapsed_ms": 340,
  "budget_remaining_ms": 4460,
  "cached": true,
  "resumable": false
}
```

### Status taxonomy — two orthogonal fields

`status`:
- `resolved` — reached a terminal response, chain complete
- `partial` — best-known URL, chain may continue
- `failed` — couldn't complete even the first hop

`stop_reason`: `terminal_response | deadline | max_hops | loop | non_http_scheme |
js_suspected | transport_error | dns_error`

Note `non_http_scheme` pairs with `status: resolved` — a deep link is a successful terminal.

### HTTP status codes

- `200` for both `resolved` and `partial` — client reads `status` from the body
- `400` for malformed input
- **Never 504.** A timeout that produced four hops is a successful partial, not a
  gateway failure. In Mutiny: `.recoverWithItem(partialResult)`, not `.failWith(...)`.

Set `Cache-Control: max-age=<remaining TTL>` so OkHttp on Android caches locally.

---

## Endpoint 2 — `/api/v2/unshorten` (unshorten.me-compatible)

Drop-in replacement. Exactly three fields, nothing else on the success path.

```json
{
  "unshortened_url": "https://www.youtube.com/",
  "shortened_url": "https://bit.ly/3DKWm5t",
  "success": true
}
```

- `shortened_url` echoes the **raw input** as the caller sent it, not the normalized
  form — clients string-compare it against what they passed.
- Profile is **hardcoded to `android`**. No `profile` query param. Ignore it if present.
- Accept and **silently ignore** an `Authorization: Token ...` header. Clients already
  configured against unshorten.me send one; rejecting it breaks drop-in for no gain.
- Always `200`, even when `success` is false. Only `400` for a missing/empty `url` param.
  Compat clients won't handle 422.

### Mapping from the rich `Result`

| Rich result | `success` | `unshortened_url` |
|---|---|---|
| `resolved` | `true` | `final_url` |
| `resolved` + `non_http_scheme` | `true` | see deep-link rule below |
| `partial` (any `stop_reason`) | `true` | `final_url` (best known) |
| `failed` | `false` | echo input |

**`success` maps to `status != failed`. No other conditions.**

Rationale: `failed` is already defined as "couldn't complete even the first hop," so
a partial always carries at least one successful hop and a `final_url` better than the
input. The compat shape can't express "incomplete," and a best-known URL serves the
client far better than discarding it. `success: false` is therefore reserved strictly
for real errors — bad input, DNS failure, transport error on hop 1.

A client that cares whether the chain finished can compare `unshortened_url` against
`shortened_url`, or use `/api/v1/resolve` and read `status` and `stop_reason`.

**Deep-link rule.** A compat client expects an http(s) URL, so for non-HTTP destinations
resolve in this order:
1. `AppIntent.fallback` (the `S.browser_fallback_url` from the intent)
2. For `Store`, the equivalent `https://play.google.com/store/apps/details?id=...`
3. Otherwise the raw `intent://` / `market://` string

### On failure

Add an `error` string alongside the three fields. A superset is safe — clients reading
only the documented three ignore it.

```json
{ "shortened_url": "...", "unshortened_url": "...", "success": false, "error": "dns_error" }
```

Use the `stop_reason` value as the error string. Free diagnostics, zero cost. Only
`transport_error` and `dns_error` can reach this path (plus input validation); every
other `stop_reason` produces a `partial`, and therefore `success: true`.

### Not implemented

unshorten.me returns `usage_count` on some plans. Omitted — single user, no quota.
Add a static value only if a specific client turns out to require the field.

---

## Caching — two levels, both Caffeine via `quarkus-cache`

**L1: whole-walk.** Key `(normalized_url, ua_profile)`. Fast path for exact repeats.

**L2: per-edge.** Key `(url, ua_profile) → next_url`. This is what makes partial results
resumable: a retry after `deadline` replays the known prefix in microseconds and spends
the full 5s budget on new ground. Also shares entries across unrelated links that funnel
through the same tracker or affiliate domain, which happens constantly.

`resumable` is computed from whether the truncated prefix is edge-cached.

### Two traps

1. **`@CacheResult` does not cache exceptions** — a failed load evicts the entry. So
   **never throw on a resolution failure.** Always return a result object carrying
   success/failure plus the partial chain. Wanted anyway: a partial chain is the useful
   output when debugging a dead link.

2. **Cookie-bearing hops are not edge-cacheable.** If hop 2 only works because hop 1 set
   a cookie, replaying hop 1 from cache skips it. Mark any hop whose response carried
   `Set-Cookie` as non-cacheable at the edge level.

TTLs: ~7d for success, ~5min for failures. Caffeine has no per-entry TTL in Quarkus
config, so this needs either two caches or an `expireAfter` policy bean.

---

## User-Agent profiles

Default profile: **android**. Not cosmetic — shorteners do real device targeting
(Bitly, Branch, AppsFlyer), so an Android UA gives the destination we'd actually land on.

Use Chrome's **reduced** UA format. UA Reduction is complete; real Chrome on Android
reports a frozen `Android 10` on model `K` regardless of hardware. Sending
`Android 14; Pixel 8` would be *more* conspicuous, not less.

```
User-Agent: Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36
Sec-CH-UA: "Chromium";v="151", "Google Chrome";v="151", "Not?A_Brand";v="24"
Sec-CH-UA-Mobile: ?1
Sec-CH-UA-Platform: "Android"
Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
Accept-Language: en-US,en;q=0.9
```

Don't send hints a real browser wouldn't volunteer unprompted (e.g. `Sec-CH-UA-Model`
only appears after a server's `Accept-CH`).

**Profiles are named config, not hardcoded strings.** Even shipping only `android` on
day one, `?profile=desktop` later should be config, not surgery. Comparing two profiles
on the same link is genuinely useful.

**UA profile is part of every cache key.** Forget this and Android answers get served
to desktop queries.

Known ceiling: UA spoofing loses to TLS/HTTP2 fingerprinting (JA3/JA4). Fine for the
simple UA-based routing most shorteners do.

---

## DNS — must go through AdGuard

Vert.x ships Netty's async DNS resolver by default, which bypasses the OS path.
Force the OS resolver:

```
-Dvertx.disableDnsResolver=true
```

Pass this as a **runtime argument in `CMD`**, not `application.properties` — Vert.x reads
it via `System.getProperty` during init, and the config file is too late.

Also set `networkaddress.cache.ttl` explicitly (0 or ~5s). Default JVM caching hides
repeat lookups from AdGuard, which defeats the logging goal. Latency cost against a LAN
resolver is negligible, and the result cache prevents most repeat resolutions anyway.

Container is allowlisted in AdGuard, so no filtering interference with tracker/affiliate
hops mid-chain. Bonus: AdGuard's query log becomes a record of every domain a suspicious
link touched.

---

## Security — STUBBED FOR NOW, INTERFACE MUST EXIST

Deliberate deferral. But the hook is structural, not a bolt-on, so build it now:

```java
interface Guard {
    void checkUrl(URI url);            // per hop
    void checkIp(String host, InetAddress ip);  // per candidate address, pre-connect
}
```

Default implementation: `AllowAll`. Wire it into the connection path so that swapping in
a real policy later is a one-class change rather than a refactor of the HTTP client setup.

Context for whoever picks this up: this service fetches attacker-controlled URLs from
inside a home LAN. Whoever creates a short link controls every hop in the chain, so they
control which address the fetcher connects to. On a flat LAN that reaches the router
admin, Proxmox, Home Assistant, the \*arr stack, IPMI, and sibling containers. Real
mitigation is IP denylisting (RFC1918, 169.254/16, IPv6 equivalents) validated at *every*
hop with the IP pinned between check and connect, plus network isolation at the firewall.
Not now, but don't design it out.

---

## URL normalization

- Lowercase scheme and host; strip default ports; drop fragment
- **Leave the path alone.** Short codes are case-sensitive — `/AbC` ≠ `/abc`
- Bare `bit.ly/abc` with no scheme → assume `https://`
- Reject non-http(s) schemes on *input* (but accept them as redirect *targets*)

---

## Native-image checklist

Verify these in the built binary, not just dev mode:

- [ ] HTTPS works outbound — check `quarkus.native.enable-https-url-handler` and the
  truststore. Classic failure: everything works in `quarkus dev`, every outbound
  HTTPS call fails in native. Smoke-test day one.
- [ ] `-Dvertx.disableDnsResolver=true` is actually taking effect (confirm via AdGuard logs)
- [ ] `networkaddress.cache.ttl` set explicitly — defaults differ under native-image
- [ ] JSON serialization of records and the sealed hierarchy round-trips
- [ ] Build JDK and Mandrel JDK versions match (both 25)

---

## Build order

1. Resolver core: HTTP chain walking, deadline propagation, hop cap, loop detection,
   body cap, partial results. Unit-testable with no server.
2. `/api/v1/resolve`, both cache levels, UA profiles.
3. `/api/v2/unshorten` as a thin projection of the same `Result`. Should be ~40 lines:
   a resource method, a 3-field record, and a mapper. If it needs more than that,
   the resolution logic has leaked into the endpoint — push it back down.
4. Meta refresh.
5. JS detection (static scan, no execution).
6. `intent://` parsing and deep-link destinations.
7. Native build + the checklist above.

## Explicitly out of scope

API keys, auth, rate limiting, user accounts, a database, headless browser rendering,
Safe Browsing / URLhaus reputation lookups, batch endpoints, a web UI.

Single user, single client (Android), homelab network.