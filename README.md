# unshortener

Self-hosted URL unshortener. Given a shortened URL, walks the redirect chain and
reports the final destination plus the hops taken, under a 5-second ceiling.

Full design, constraints, and rationale: [`plan.md`](./plan.md). Contributor
notes (architecture, commands, known gaps): [`CLAUDE.md`](./CLAUDE.md).

## Run

```shell script
./gradlew quarkusDev
```

Dev UI: <http://localhost:8080/q/dev/>.

## API

```
GET /api/v1/resolve?url=<encoded>&profile=android     # rich
GET /api/v2/unshorten?url=<encoded>                    # unshorten.me-compatible
GET /healthz
```

## Build

```shell script
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```
