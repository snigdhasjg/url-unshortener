pluginManagement {
    val quarkusPluginVersion = settings.providers.gradleProperty("quarkusPluginVersion").get()
    val quarkusPluginId = settings.providers.gradleProperty("quarkusPluginId").get()
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        id(quarkusPluginId) version quarkusPluginVersion
    }
}
rootProject.name="unshortener"
