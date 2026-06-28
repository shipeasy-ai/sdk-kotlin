// Top-level build file. Plugin versions are declared here with `apply false`
// and applied in the module build files (app/build.gradle.kts).
//
// Version trio (mutually compatible — do not bump one without the others):
//   AGP                          8.5.2
//   Kotlin                       2.0.20
//   Kotlin Compose compiler      org.jetbrains.kotlin.plugin.compose 2.0.20 (matches Kotlin)
//   Compose BOM                  2024.09.03 (pulls compose-compiler-independent runtime)
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
