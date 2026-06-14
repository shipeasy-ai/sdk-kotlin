plugins {
    kotlin("jvm") version "1.9.23"
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish") version "0.28.0"
}

group = "ai.shipeasy"
version = "0.3.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // For AnonIdFilter only. compileOnly: the servlet container supplies it at
    // runtime, so it adds nothing to consumers' deployments; non-servlet users
    // never load the filter class.
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(11) }

tasks.test { useJUnitPlatform() }

// Publishing is handled by the vanniktech plugin (Central Portal compatible).
// Run via `./gradlew publishAllPublicationsToCentralPortalRepository`.

