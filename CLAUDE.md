# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This is a freshly scaffolded Quarkus application named `unshortener` (group `com.snigji`). As of now, `src/main/java` and `src/main/resources/application.properties` are empty — no application code, REST endpoints, or tests exist yet. There is no git repository initialized in this directory.

## Commands

Build and run:
```shell
./gradlew quarkusDev          # run in dev mode with live reload; Dev UI at http://localhost:8080/q/dev/
./gradlew build                # compile, test, and package -> build/quarkus-app/quarkus-run.jar
./gradlew test                 # run tests
./gradlew test --tests "com.snigji.SomeTest"   # run a single test class
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

Native executable (requires GraalVM, or use `-container-build` to build in a container instead):
```shell
./gradlew build -Dquarkus.native.enabled=true
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
./build/unshortener-1.0.0-SNAPSHOT-runner
```

Docker images (build the jar with `./gradlew build` first): Dockerfiles for JVM, legacy-jar, native, and native-micro variants live in `src/main/docker/`.

## Architecture

- Build system: Gradle (Kotlin DSL) with the Quarkus Gradle plugin (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`). Quarkus platform/plugin versions are pinned in `gradle.properties` (currently 3.39.4) and should be updated there, not per-dependency.
- Java 25 (`sourceCompatibility`/`targetCompatibility`), with `-parameters` enabled at compile time (needed for Quarkus JAX-RS/CDI parameter reflection).
- Extensions currently in use: `quarkus-rest-jackson` (REST + JSON), `quarkus-smallrye-health` (health checks), `quarkus-cache`, `quarkus-vertx`, `quarkus-arc` (CDI). Add new capabilities via Quarkus extensions in `build.gradle.kts` rather than pulling in unrelated libraries directly.
- Since there is no source yet, there is no established package layout or endpoint structure to follow — the first REST resources, services, and config should establish the convention for what follows.
