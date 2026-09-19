plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId = providers.gradleProperty("quarkusPlatformGroupId").get()
val quarkusPlatformArtifactId = providers.gradleProperty("quarkusPlatformArtifactId").get()
val quarkusPlatformVersion = providers.gradleProperty("quarkusPlatformVersion").get()

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-cache")
    implementation("io.quarkus:quarkus-vertx")
    implementation("io.quarkus:quarkus-arc")

    // Vert.x WebClient — deliberately not a Quarkus extension.
    // quarkus-vertx supplies the Vertx instance only. Version is managed by the
    // Quarkus platform BOM, so versionless is correct. Do not "fix" either of these.
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")

    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
}

group = "com.snigji"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
