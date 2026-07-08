pluginManagement {
    // google() is needed to resolve the Android Gradle plugin for the (guarded)
    // :android module; gradlePluginPortal()/mavenCentral() cover Kotlin + vanniktech.
    // Superset of the default (portal-only), so the pure-JVM build is unaffected.
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

rootProject.name = "sdk-kotlin"

// The optional, generated OpenAPI admin client ships as a separate artifact
// (ai.shipeasy:shipeasy-admin-kotlin) so the flags SDK keeps zero new deps.
include(":admin")

// The Android client artifact (ai.shipeasy:shipeasy-kotlin-android — a
// SharedPreferences-backed AnonStore + configureAndroid()) applies the Android
// Gradle plugin, so it builds ONLY when an Android SDK is available. Guarded so
// the pure-JVM core build / test / Maven-publish never needs the Android SDK.
// Enable on an Android-capable runner with `-PwithAndroid` or by exporting
// ANDROID_HOME / ANDROID_SDK_ROOT.
val androidCapable = startParameter.projectProperties.containsKey("withAndroid") ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null
// AGP itself requires Java 17+, so JDK-11 builds (the oldest CI leg) must skip
// the module even on runners that ship an Android SDK.
val androidEnabled = androidCapable && JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)
if (androidEnabled) include(":android")
