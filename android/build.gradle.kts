import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

// The Android client artifact: a SharedPreferences-backed AnonStore +
// configureAndroid() convenience on top of the pure-JVM core (ai.shipeasy:
// shipeasy-kotlin). Applies the Android Gradle plugin, so this module is only
// included when an Android SDK is present (see settings.gradle.kts). All plugin
// versions resolve from the root plugins block — never state one here.
plugins {
    id("com.android.library")
    kotlin("android")
    id("com.vanniktech.maven.publish")
}

group = "ai.shipeasy"
version = rootProject.version

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ai.shipeasy.android"
    compileSdk = 34
    defaultConfig {
        // SharedPreferences is available on every supported Android API level.
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    // The pure-JVM core (ai.shipeasy:shipeasy-kotlin). `api` so consumers get
    // ShipeasyClient / configureClient / AnonStore transitively.
    api(project(":"))
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    // Publish the release variant with sources + javadoc jars.
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))
    coordinates("ai.shipeasy", "shipeasy-kotlin-android", project.version.toString())
    pom {
        name.set("shipeasy-kotlin-android")
        description.set("Shipeasy native Android client — SharedPreferences-backed anonymous-id persistence + configureAndroid(). Companion to ai.shipeasy:shipeasy-kotlin.")
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
