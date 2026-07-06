import com.vanniktech.maven.publish.SonatypeHost

// Separate, opt-in artifact: the generated OpenAPI admin client. Plugin versions
// resolve from the root project's `plugins {}` block (Kotlin 1.9.23 + vanniktech
// 0.28.0), so they're declared here without versions.
plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

group = "ai.shipeasy"
version = rootProject.version

repositories { mavenCentral() }

dependencies {
    // OkHttp4 + Moshi (reflection adapter — @JsonClass(generateAdapter=false),
    // so no KSP) + coroutines. These live ONLY on this artifact.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(11) }

tasks.test { useJUnitPlatform() }

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("ai.shipeasy", "shipeasy-admin-kotlin", project.version.toString())
    pom {
        name.set("shipeasy-admin-kotlin")
        description.set("Shipeasy Admin API client for Kotlin — generated from the OpenAPI spec (OkHttp + Moshi). Optional companion to ai.shipeasy:shipeasy-kotlin.")
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
