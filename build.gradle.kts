import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "1.9.23"
    id("com.vanniktech.maven.publish") version "0.28.0"
}

group = "ai.shipeasy"
version = "0.4.0"

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

// Publishing to Maven Central via the Sonatype Central Portal, handled by the
// vanniktech plugin. Credentials + the in-memory GPG signing key are supplied
// by CI as ORG_GRADLE_PROJECT_* env vars (see .github/workflows/publish.yml).
// Run `gradle publishAndReleaseToMavenCentral`.
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    // Distinct artifactId from the Java SDK (ai.shipeasy:shipeasy) — both target
    // the same group, so the Kotlin gem ships as ai.shipeasy:shipeasy-kotlin.
    coordinates("ai.shipeasy", "shipeasy-kotlin", project.version.toString())
    pom {
        name.set("shipeasy-kotlin")
        description.set("Shipeasy server SDK for Kotlin — flags, configs, experiments, metrics.")
        url.set("https://shipeasy.dev")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("shipeasy")
                name.set("Shipeasy")
                email.set("sdk@shipeasy.ai")
                organization.set("Shipeasy, Inc.")
                organizationUrl.set("https://shipeasy.ai")
            }
        }
        scm {
            url.set("https://github.com/shipeasy-ai/sdk-kotlin")
            connection.set("scm:git:https://github.com/shipeasy-ai/sdk-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/shipeasy-ai/sdk-kotlin.git")
        }
    }
}
