plugins {
    kotlin("jvm") version "1.9.23"
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish") version "0.28.0"
}

group = "ai.shipeasy"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(11) }

tasks.test { useJUnitPlatform() }

// Publishing is handled by the vanniktech plugin (Central Portal compatible).
// Run via `./gradlew publishAllPublicationsToCentralPortalRepository`.

