# Structure & readability review — 2026-09-29

Consolidated findings from five parallel reviews of `src/main/java` (working tree
was clean, so all five audited the full tree rather than a diff):

- `@code-review` (high effort, general correctness + cleanup)
- `pr-review-toolkit:code-simplifier`
- `pr-review-toolkit:type-design-analyzer`
- `pr-review-toolkit:comment-analyzer`
- `pr-review-toolkit:silent-failure-hunter`

Each was pointed at `CLAUDE.md` first and told not to flag documented, intentional
tradeoffs (the `Guard`/`AllowAllGuard` stub, cookie scoping by `Domain` only, 200-
on-bad-input for v2, `remoteIp` always `null`, two plain Caffeine tiers instead of
`@CacheResult`, the `via` override on cache hit, the desktop default UA profile).
None of them did.

Findings marked **⚡ converged** were raised independently by two or more reviewers
from different angles — treat those as higher confidence.

---

## Tier 1 — Correctness bugs (real behavior, not just style)

**Status: fixed**, except #4 below (verified not reachable). See the working tree
for the diff; not committed yet.

1. **`EdgeCache` TTL never expires for a popular failing URL.** ⚡ *converged
   (code-review, code-simplifier)*
   `RedirectResolver.step()` (`RedirectResolver.java:88`) unconditionally
   `edgeCache.put()`s after *every* hop, including cache **hits**. Caffeine's
   `expireAfterWrite` resets on each put, so a transient failure cached in the
   5-min failure tier for a popular tracker domain never actually ages out and
   is never retried, as long as it keeps getting hit. It also re-persists the
   walk-relative `via` field for whichever walk hit it last.
   → Skip the `put` when the computation came from a cache hit.
   **Fixed:** `edgeCache.put` moved from `step()` into the cache-miss branch of
   `computeHop`, so a hit never re-writes the entry (`RedirectResolver.java`).

2. **Hedge/fallback GET can overshoot its real remaining budget.**
   (code-review) `remainingHopMs()` (`RedirectResolver.java:190`) floors the
   fallback GET's timeout at `FLOOR_MS` (400ms) even when less than 400ms
   actually remains in the hop's own budget. Traced concretely: a ~410ms hop
   can overshoot to ~605ms, eating into the 200ms `RESPONSE_RESERVE` between
   `RESOLVER_BUDGET` and `API_CEILING` — risking the hard-cutoff path that
   `ResolverService.hardCutoffFallback` says should "never fire."
   **Fixed:** `remainingHopMs` now floors at 1ms (the minimum Vert.x accepts as
   a real timeout) instead of `FLOOR_MS` (400ms), and the HEAD leg's retry is
   now also re-timed against the remaining budget instead of restarting with
   the hop's full original timeout.

3. **`CookieJar` mis-scopes a cookie with an explicit-but-empty `Domain=;`.**
   (code-review) `extractDomain()` (`CookieJar.java:38`) returns
   `Optional.of("")` for a blank Domain attribute — *present*, not absent — so
   `store()`'s `.orElse(responseHost)` never fires and the cookie is filed
   under domain `""`. `cookieHeaderFor()` then can't match it against any real
   hostname; the cookie is silently never replayed.
   **Fixed:** `extractDomain` now returns `Optional.empty()` when the Domain
   value is empty (after stripping a leading dot), so `store()`'s
   `.orElse(responseHost)` fires as intended.

4. **`IntentUrlParser.extractPackageId` has no catch around
   `URLDecoder.decode`.** ⚡ *converged (code-simplifier #17, silent-failure-hunter #3)*
   (`IntentUrlParser.java:50-56`) A malformed `market://details?id=%zz` throws
   inside `.map(interpret)`, which the broad catch-all in `fetchHop` (see Tier
   2 #1) reclassifies as `TRANSPORT_ERROR` instead of the intended
   `NON_HTTP_SCHEME`/fallback-to-raw-id behavior its sibling `extractFallback`
   already has.
   **Not reachable — no code change.** `java.net.URI` itself rejects a
   malformed percent-escape (confirmed in jshell: `market://details?id=%zz`
   throws `URISyntaxException`, "Malformed escape pair"), so `resolveLocation`
   already returns `null` and short-circuits to a terminal response before
   `extractPackageId` ever sees a string `URLDecoder.decode` can choke on.

5. **`IntentUrlParser.extractFallback` swallows all exceptions with zero
   logging.** (silent-failure-hunter) Catches bare `Exception`
   (`IntentUrlParser.java:39-44`), not just the `IllegalArgumentException`
   that `URLDecoder.decode`/`URI.create` can throw, and logs nothing. A
   malformed `browser_fallback_url` silently becomes `null` with no way to
   tell "present but unparseable" from "genuinely absent."
   **Fixed:** catch narrowed to `IllegalArgumentException` (what
   `URLDecoder.decode`/`URI.create` actually throw), with a DEBUG log line
   recording the raw value. Unlike #4, this one does happen in practice: a
   decoded fallback URL containing a literal space, for instance, fails
   `URI.create` (confirmed in jshell).

6. **`UrlNormalizer.normalizeHttp` silently returns the un-normalized URI on
   failure**, with no logging (`UrlNormalizer.java:67-71`). This feeds
   loop-detection keys and cache keys, so a silent failure here could miss a
   loop or split a cache entry that should be shared.
   **Fixed, and found worse than described on investigation:** the original
   code used the multi-arg `URI` constructor, which takes *decoded* path/query
   components — so it silently corrupted any redirect target carrying encoded
   delimiters, not just the rare re-parse failure. Confirmed in jshell: a
   tracker URL embedding another URL as
   `?u=https%3A%2F%2Fx.com%2Fa%3Fb%3D1%26c%3D2` came out with the embedded
   query merged into the outer one; `/a%2Fb` became `/a/b`; `?q=a%2Bb` became
   `?q=a+b`. Rewrote to rebuild from the *raw* (still-encoded) components and
   re-parse that string, so encoding survives untouched. Also fixed a second
   bug: a host `getHost()` can't parse (e.g. one containing `_`) silently
   dropped the entire authority — now returned unchanged instead. The
   `URISyntaxException` catch should be unreachable now; kept, logging at WARN
   instead of failing silently.

7. **`UnshortenMapper`'s Javadoc claim is false for an edge case, and it's
   test-backed.** (comment-analyzer) It says a partial "always carries at
   least one successful hop and a final_url better than the input"
   (`UnshortenMapper.java:11-12`) — but `JS_SUSPECTED` on the very first hop
   maps to `PARTIAL` unconditionally, and `RedirectResolverTest
   .flagsSuspectedJsRedirectAsPartial` already asserts `finalUrl() == input`
   in that case. Real consequence: a v2 client can get `success:true` with
   `unshortened_url == shortened_url`.
   **Fixed as a doc-only change** (this is intended behavior per `plan.md`'s
   own compat-endpoint rationale, which already tells clients to compare
   `unshortened_url` against `shortened_url`): reworded `UnshortenMapper`'s
   Javadoc and the matching paragraph in `docs/plan.md` to say a partial
   carries the best-known `final_url`, which can equal the input verbatim.
   `Status.from` itself is untouched.

---

## Tier 2 — Architectural robustness (the "never throws" boundary)

These come from `silent-failure-hunter` and form one connected root cause —
worth doing together, likely via a single new `StopReason.INTERNAL_ERROR`.

**Status: fixed**, all three, via a single `StopReason.INTERNAL_ERROR` as
suggested. See the working tree for the diff; not committed yet.

1. **CRITICAL — `RedirectResolver.fetchHop`'s catch-all conflates programming
   bugs with network failures.** (`RedirectResolver.java:184-186, 309-319`)
   The `onFailure()` after `interpret()` has no exception-type filter, so it
   catches *anything* — NPEs, decode/parse bugs, anything in the meta-refresh
   or JS-redirect scanners — and `classifyError` only has two buckets: DNS or
   `TRANSPORT_ERROR`. Logged only at DEBUG (normally off in prod), so a real
   code defect is invisible in both the `Result` model and the logs.
   → Either narrow the recovery to known transport exception types, or add a
   distinct `StopReason.INTERNAL_ERROR` bucket logged at `LOG.error`.
   **Fixed:** added `interpretSafely`, a thin wrapper around `interpret()` that
   catches only what `interpret()` itself (and everything it calls — cookie
   parsing, meta-refresh/JS scanning, `IntentUrlParser`) can throw, converting
   it to `HopComputation`/`StopReason.INTERNAL_ERROR` logged at `LOG.error`.
   The existing outer `onFailure()`/`classifyError` is untouched and now only
   ever sees genuine transport/DNS exceptions from the HEAD/GET legs — no
   guessing required. Marked non-cacheable, so a code bug is never replayed
   from `EdgeCache` for up to 5 minutes as if it were a stable outcome. Not
   directly testable today: every callee `interpret()` reaches is already
   `Optional`-returning or self-catching (including after the Tier 1 fixes to
   `UrlNormalizer`/`IntentUrlParser`), so there's currently no reachable input
   that trips it — this is deliberate defense-in-depth for a regression, not
   a live bug.

2. **HIGH — no failure-path handling in `ResolverService`/REST layer breaks
   the real client contract.** `.ifNoItem().after(...)` in both resources
   (`ResolveResource.java:27-33`, `UnshortenResource.java:43-47`) only reacts
   to "no item within duration," not to an upstream failure. If `resolve()`
   ever actually fails (e.g. via gap #1), it falls through to
   `UnexpectedExceptionMapper` → a 500 with `{"error": "internal"}`. Per
   project memory, **the real Android v2 client ignores HTTP status and
   requires `success`/`unshortened_url` in the body** — this is the one
   scenario the whole `Result`/`Status` design exists to prevent, and it's
   also the one that would actually break production.
   → Add `.onFailure().recoverWithItem(...)` in `ResolverService.resolve()` (or
   each resource) converting any unexpected failure into a proper
   `Result`/`UnshortenResponse.failure(...)`.
   **Fixed** exactly as suggested: `ResolverService.resolve()` now has its own
   `onFailure().recoverWithItem(...)`, logged at `LOG.error`, converting any
   failure that reaches this facade into a `Result` with
   `Status.FAILED`/`StopReason.INTERNAL_ERROR` — which both `ResolveResource`
   and `UnshortenMapper` already know how to render correctly (the latter as
   `success:false` with a body, not a bare 500). Covered by a new
   `ResolverServiceTest` using a stubbed `RedirectResolver` that always fails.

3. **MEDIUM — the "never throws" guarantee is emergent, not enforced.**
   `guard.checkUrl(target)` runs synchronously on hop 1, *outside* any Mutiny
   `.flatMap`/`.map` wrapper (`RedirectResolver.java:130`) — dormant today
   only because `AllowAllGuard` is a no-op. `Guard`'s own Javadoc documents
   the intended real implementation as something designed to reject/throw, so
   this is a landmine for whoever wires in a real `Guard`.
   → Add one explicit safety net at `resolve()`'s true boundary rather than
   relying on `fetchHop` happening to catch everything.
   **Fixed:** `RedirectResolver.resolve()`'s whole body now runs inside
   `Uni.createFrom().deferred(...)`, with its own `onFailure().recoverWithItem(...)`
   at the end. This closes a gap wider than just the guard call: without
   `deferred`, hop 1's entire synchronous chain (`step`/`computeHop`/`fetchHop`,
   including `guard.checkUrl`) runs eagerly the moment `resolve()` is called,
   before any `Uni` exists for a failure handler to attach to — a thrown
   exception there would have escaped past every `onFailure()` in the class,
   `fetchHop`'s included. The recovery path reports whatever hops were already
   gathered via a captured `WalkState`, so a bug late in a chain still returns
   a proper partial instead of discarding the hops already walked. Covered by
   a new `neverThrowsWhenGuardRejectsSynchronously` test using a `Guard` stub
   that throws.

---

## Tier 3 — Type-design: invariants enforced by convention, not by the type

From `type-design-analyzer`. None are urgent — today's call sites are all
careful — but each converts "true because nobody's broken it yet" into "true
by construction" for a small cost.

**Status: fixed**, all eight. See the working tree for the diff; not
committed yet.

1. **`Result`'s `status`/`stopReason` pairing is unenforced.** Nothing stops
   `new Result(..., Status.RESOLVED, StopReason.DEADLINE, ...)` from
   compiling, even though CLAUDE.md calls `Status.from` "the one place that
   decides" this. → compact constructor asserting `status ==
   Status.from(stopReason, ...)`.
   **Fixed**, adapted slightly: `Result` has no `anyHopCompleted` field to
   pass to `Status.from` directly, so added `Status.isConsistent(status,
   reason)` — true if `status` is what `from(reason, ...)` could produce for
   *either* value of `anyHopCompleted` — and call it from `Result`'s compact
   constructor. Weaker than pinning the exact boolean, but it still rejects
   the review's own example (`RESOLVED` + `DEADLINE`: `from(DEADLINE, *)` is
   always `PARTIAL`) without requiring every `Result`-constructing call site
   (there are four, two of them added by the Tier 2 fix) to agree on one
   formula for "did an earlier hop complete."
2. **`Destination.AppIntent.fallback` is a nullable `URI`**, inconsistent with
   `Optional<String>` used elsewhere (e.g. `UaProfilesConfig.Profile`).
   **Fixed:** changed to `Optional<URI>`, updated both call sites
   (`RedirectResolver.resolveRedirectTarget`, `UnshortenMapper.deepLinkAwareUrl`).
   Verified serialization through the real Quarkus/Jackson stack, not just
   in-process — added `resolveSerializesAppIntentFallbackAsAPlainStringOverTheWire`
   to `ApiEndpointsTest`, confirming it comes back as a plain string over
   `/api/v1/resolve`, not `Optional`'s internal shape.
3. **`Destination.Store.packageId` has zero validation** — and
   `RedirectResolver.java:274-275` can hand it a raw URI scheme-specific part
   with no shape check.
   **Fixed:** `Store`'s compact constructor now rejects a blank `packageId`.
   `resolveRedirectTarget` checks for that case explicitly *before*
   constructing one (a bare `market://` with no extractable id is a
   degenerate response from the target, not a bug in our own parsing) and
   falls back to `Destination.Unresolved` — not a try/catch around the
   constructor, since this is an expected input shape, not an exceptional one.
4. **`UaProfile`'s immutability guarantee is bypassable** — the canonical
   constructor is public/unguarded, and `RedirectResolverTest.java:44` already
   constructs it directly, proving the "always via `from()`" convention isn't
   load-bearing today only by luck.
   **Fixed:** added a compact constructor doing `headers = List.copyOf(headers)`,
   so the defensive copy `from()` used to do alone now holds regardless of
   entry point. `from()` no longer needs its own `List.copyOf` call.
5. **`ResolverLimits`'s numeric relationships are comment-only**
   (`4800+200=5000`, `floor<cap`) — a `static {}` block with asserts would
   make this fail at class-load time instead of silently.
   **Fixed** exactly as suggested, with explicit `if`/`throw` rather than the
   `assert` keyword (which is a no-op unless the JVM runs with `-ea`, and this
   needs to hold in production). Tier 4 separately flags `RESPONSE_RESERVE` as
   otherwise-unused and suggests defining `RESOLVER_BUDGET` as
   `API_CEILING.minus(RESPONSE_RESERVE)`; left untouched here since that's a
   Tier 4 item, but doing it would make this particular check trivially true
   by construction rather than a runtime assertion.
6. **`WalkState.cookieJar()` leaks the live mutable `CookieJar`** instead of
   exposing just the two operations `RedirectResolver` actually needs.
   **Fixed:** removed the `cookieJar()` accessor; `WalkState` now exposes
   `cookieHeaderFor(host)` and `storeCookies(host, headers)` directly,
   delegating internally. `RedirectResolver`'s two call sites updated.
7. **`UaProfileRegistry.resolve` does exact-case lookup** — `"Android"` 400s
   even if `"android"` is configured, undocumented as intentional. Also: no
   validation that `userAgent()` is non-blank, despite Hibernate Validator
   already being a project dependency.
   **Fixed:** lookup is now case-insensitive (profile names are config keys a
   client passes in a query param, not case-sensitive identifiers), returning
   the canonical stored key so cache keys built from `profile().name()` don't
   fragment across request casing. Added `@NotBlank` to
   `UaProfilesConfig.Profile.userAgent()` — Quarkus validates `@ConfigMapping`
   interfaces against Bean Validation constraints at startup, confirmed by
   `./gradlew build` still passing (today's `application.yml` values satisfy it).
8. Minor: `WalkState`'s constructor doesn't null-check `rawInput`/`profile`
   (NPE downstream instead of fail-fast); `CookieJar`'s silent-skip of
   malformed `Set-Cookie` headers isn't labeled as intentional at the point it
   happens.
   **Fixed:** both, via `Objects.requireNonNull` in `WalkState`'s constructor
   and a one-line comment at the `CookieJar` skip site.

---

## Tier 4 — Structural duplication / simplification

From `code-simplifier`. No behavior change intended for any of these except
where noted.

**Status: fixed**, all except the two explicitly-optional/your-call items
noted below (left alone on purpose). `./gradlew test` (all 65 tests, no new
or changed assertions needed for these) and `./gradlew build` both still
pass — consistent with "no behavior change intended."

- **Cache key format duplicated and APIs mismatched.** ⚡ *converged
  (code-review #6)* `EdgeCache.key` builds `profile + "|" + url`
  (`EdgeCache.java:39-41`); `ResolverService` builds the same string inline
  (`ResolverService.java:42`) for `WholeWalkCache`. Give both caches the same
  `(URI, profile)` signature and keep key-building inside the cache classes.
  **Fixed:** `WholeWalkCache.get`/`put` now take `(URI, profile)`, building the
  key internally, same shape as `EdgeCache`. `ResolverService` no longer
  builds a cache key itself.
- **`RESPONSE_RESERVE` is unused except in a javadoc link.** ⚡ *converged
  (code-review #4)* Define `RESOLVER_BUDGET = API_CEILING.minus(RESPONSE_RESERVE)`
  so the relationship is enforced in code, not just stated.
  **Fixed** exactly as suggested. Tier 3's static class-load assertion
  checking this same relationship is now tautological but left in place —
  cheap insurance against a future edit reverting `RESOLVER_BUDGET` back to
  an independent literal.
- **`GET_FALLBACK_STATUSES` is an ad-hoc, incident-driven list with no
  rationale.** ⚡ *converged (comment-analyzer, code-review #5)* `{404, 405,
  501, 400, 403}` grew one host-quirk at a time; consider a general rule
  ("any non-2xx/3xx HEAD status triggers a confirmatory GET") instead.
  **Fixed the "no rationale" complaint only, deliberately not the behavior.**
  A general rule changes real network behavior (many more statuses would now
  trigger a confirmatory GET) with no test coverage backing that specific
  change, and the review itself only "considers" it, doesn't require it —
  out of scope for a tier whose own premise is no behavior change. Added a
  comment explaining what's known about each status instead, and reordered
  the set numerically (also folds in the "written out of numeric order" tidy-up
  below).
- `WalkState.rawInput` is redundant/misnamed — it's already normalized;
  `buildResult` should just use `normalizedInput`.
  **Fixed:** removed the field entirely (confirmed via grep it had no other
  reader); `WalkState`'s constructor is now just `WalkState(UaProfile)`.
- `WalkOutcome` duplicates `HopOutcome.Terminal` exactly — can be deleted.
  **Fixed:** deleted. `step()` now returns `Uni<HopOutcome.Terminal>` directly
  — the terminal branch of its `switch` no longer even needs to reconstruct
  a value, since `t` already *is* the right shape.
- `step()` repeats the same early-stop construction 3×; `interpret()` repeats
  the same `Hop`-building pattern 4×; `fetchHop`'s `SimpleEntry` tagging
  repeated 4× (→ a `MethodResponse` record); `isDnsFailure`/`isStaleConnection`
  are the same cause-chain walk (→ one `hasCause(Throwable, Class)` helper);
  the stale-connection retry is written twice.
  **Fixed, all five:** a private `stop(URI, StopReason)` helper for `step()`;
  a private static `hopOf(...)` helper for `interpret()`/`interpretSafely()`
  (5 call sites, not just the 4 originally in `interpret()` alone —
  `interpretSafely`'s own catch-branch Hop shared the same shape);
  `fetchHop`'s tagging now uses a nested `MethodResponse(String method,
  HttpResponse<Buffer> response)` record instead of `AbstractMap.SimpleEntry`;
  `hasCause(Throwable, Class<? extends Throwable>)` backs both
  `isDnsFailure`/`isStaleConnection`, kept as named wrappers rather than
  inlined at call sites, for readability; `withStaleConnectionRetry(Uni)`
  backs both `getOnce` and `headLeg`.
- `EdgeCache`/`WholeWalkCache` are structurally identical (optional: extract a
  `TieredCache<V>`). **Left alone, on purpose** — explicitly marked optional,
  and a generic wrapper for two four-line classes is the kind of abstraction
  not worth its own indirection.
- Hard-cutoff `.ifNoItem().after(...)` duplicated in both REST resources —
  flagged as "your call" since CLAUDE.md calls this timeout "resource-level"
  deliberately; may be intentional given the same wording.
  **Left alone, on purpose** — per the review's own hedge and CLAUDE.md's
  wording.
- `UrlNormalizer` re-implements `isHttp` inline instead of reusing the
  existing method. **Fixed:** `normalizeInput` now calls `isHttp(uri)`.
- `withVia` copies all eight `Hop` fields by hand — belongs on the record
  itself, next to `Result.withCached`. **Fixed:** added `Hop.withVia(HopVia)`;
  `RedirectResolver.withVia` now just calls it.
- Package direction is inverted: `service.UnshortenMapper` imports
  `rest.UnshortenResponse` — the service layer depends on the REST layer.
  **Fixed:** moved `UnshortenResponse` from `rest` to `service` (`git mv`,
  preserving history) — it's the compat wire-shape the service layer
  produces, REST only serializes it. `UnshortenResource`'s exception mapper
  (the one other place that constructs one) now imports it from `service`.
- `CookieJar` cleanups: magic number `7` instead of `"Domain=".length()`;
  double `Optional.of` in a ternary; hand-built `StringBuilder` join instead
  of `Collectors.joining("; ")`; a dead null-check on a list that's never
  actually null.
  **Fixed**, except the "double `Optional.of`" one — already resolved as a
  side effect of the Tier 1 `Domain=;` fix, which restructured that ternary
  into an if/isEmpty check. The dead null-check: confirmed via the one real
  caller (`response.headers().getAll("Set-Cookie")`, which Vert.x never
  returns `null` from) that it's genuinely unreachable, not just apparently so.
- Minor tidy-ups: `Optional<String>` used as a method parameter in two
  places; `GET_FALLBACK_STATUSES` written out of numeric order;
  `WebClientProducer`'s magic numbers (2000ms connect timeout, pool size 64)
  could live in `ResolverLimits`; a couple of methods return `null` where
  `Optional` would match the rest of the codebase's style.
  **Fixed, all four:** `ResolverService.resolve`/`UaProfileRegistry.resolve`
  now take a plain nullable `String` instead of `Optional<String>` — both
  call sites (`ResolveResource`, `UnshortenResource`) simplify too, no longer
  needing to manufacture an `Optional` just to make the call; blank-or-null
  still falls back to the default profile, same as before.
  `GET_FALLBACK_STATUSES` reordered (see above). `ResolverLimits` gained
  `CONNECT_TIMEOUT_MS`/`MAX_POOL_SIZE`, referenced from `WebClientProducer`.
  `resolveLocation`/`tryParse` (private, two internal call sites) now return
  `Optional<URI>` instead of a nullable one.

---

## Tier 5 — Comment / documentation accuracy

From `comment-analyzer`. No comment rot of the "obviously redundant" kind
turned up — all 30 inline `//` comments in `src/main/java` carry real
rationale.

- **`NetworkConfig.java:13-14`** still references native-image behavior,
  which was dropped in an earlier commit that updated CLAUDE.md/plan.md/
  application.yml/Dockerfiles but missed this one comment.
- **CLAUDE.md itself has drifted from the code.** It says `UrlNormalizer`,
  "like `RedirectResolver`," throws `BadRequestException` — but
  `RedirectResolver`'s own class Javadoc says the opposite ("Never throws...
  always completes with a Result"), confirmed by a repo-wide grep showing
  zero `BadRequestException` references in that file. The in-code comment is
  the accurate one; CLAUDE.md's wording should be fixed (drop "like
  RedirectResolver").
- `Hop.remoteIp` has no in-code comment noting it's always `null` — that's
  currently documented only in CLAUDE.md, so a consumer of the wire JSON has
  no in-repo signal it's a known gap rather than a bug.
- The `android`→`desktop` default-profile deviation lives only in this
  session's private memory, not the repo (`application.yml:14` has zero
  comment on it, and it contradicts plan.md's explicit "Default profile:
  android. Not cosmetic..." paragraph). Worth a short comment recording the
  deviation so it survives outside the memory store.
- `UaProfile.java:7`'s "exact order a real browser would send them" is a
  strong, unverified claim — flagged as possibly wrong (real Chrome sends
  `sec-ch-ua*` before `User-Agent`) but not confirmed against a HAR capture.
- `UaProfilesConfig.java`'s comments reference `application.properties` and an
  outdated "shipping only android on day one" framing — the file is
  `application.yml` and `desktop` already exists as the default.

**Positives called out** (worth preserving as house style): `Status.from()`'s
Javadoc, both cache classes' TTL-rationale comments, `Guard.java` as "a model
of documenting a deliberate stub," and `ApiEndpointsTest`'s comments on the
compat-endpoint 400-vs-200 distinction (more precise than CLAUDE.md's own
blanket statement on the same topic).

---

## Suggested next step

Fix Tier 1 + Tier 2 together on a branch (they share root causes and the
`StopReason.INTERNAL_ERROR` addition), run `./gradlew test`, then `/simplify`
+ `/security-review` on that diff before opening a PR. Tier 3–5 are lower
risk and can follow separately.
