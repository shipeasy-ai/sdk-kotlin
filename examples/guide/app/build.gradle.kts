plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 moved the Compose compiler into its own Gradle plugin; the
    // version is implied by the Kotlin plugin version (2.0.20).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.shipeasy.guide"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.shipeasy.guide"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // AndroidX + Jetpack Compose only. Versions of the individual Compose
    // artifacts are governed by the BOM, so they stay mutually consistent.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // NOTE: the Shipeasy Kotlin SDK is intentionally NOT wired into the app UI
    // yet (the cards still show placeholders). It IS pulled in for unit tests so
    // GuideEntitiesTest can mock every value via Engine.forTesting(). Resolved
    // from Maven Central — Engine.forTesting()/override* shipped in 0.8.0.
    //
    // To make the values on each card live in the app too, promote this to
    //   implementation("ai.shipeasy:shipeasy-kotlin:<latest>")
    // and replace the `// TODO: once ai.shipeasy:shipeasy-kotlin is installed`
    // blocks in MainActivity.kt with the real calls.
    testImplementation("ai.shipeasy:shipeasy-kotlin:0.8.0")
    // Engine touches kotlinx-coroutines at class-init time (CoroutineScope in
    // its `init`); keep it on the test classpath so the engine loads under a
    // plain JVM unit test.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Plain JVM unit test (no emulator / instrumentation). JUnit 4 is the
    // Android default for `src/test`.
    testImplementation("junit:junit:4.13.2")
}
