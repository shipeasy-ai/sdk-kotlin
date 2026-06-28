# Shipeasy · Kotlin Entity Guide (Android sample)

A tiny, runnable **Jetpack Compose** Android app that doubles as a one-page
guide to every Shipeasy entity you can read from the Kotlin SDK. It's a single
scrollable screen — one styled, on-brand card per entity:

1. **Feature flag** (`new_checkout`) — a boolean on/off switch with targeting + rollout.
2. **Dynamic config** (`billing_copy`) — a typed JSON blob you change without deploying.
3. **A/B experiment** (`checkout_button`) — splits users into variants and measures a metric.
4. **Kill switch** (`payments_paused`) — an operational off-switch for incident response.
5. **Event / metric** (`checkout_completed`) — fire-and-forget events that power metrics.
6. **i18n label** (`hero.title`) — server-managed copy you translate + publish (Kotlin i18n is a follow-up; shown for completeness).
7. **Error reporting** (`see()`) — structured, consequence-first error reports.

## ⚠ SDK not wired yet

This sample **does not** depend on `ai.shipeasy:shipeasy-kotlin` and makes **zero
network calls**. Every value shown on a card is a **hardcoded placeholder**
Kotlin constant (see [`Entities.kt`](app/src/main/java/ai/shipeasy/guide/Entities.kt)).

Each card also shows the **real SDK call** that would produce its value, and the
corresponding `// TODO: once ai.shipeasy:shipeasy-kotlin is installed` block is
left inline in `Entities.kt`. The app runs standalone so you can see the layout
and the calls before installing anything.

## How to run

**Android Studio (recommended)**

1. Open `examples/guide` in Android Studio (Giraffe or newer).
2. Let it sync Gradle. If it reports a missing Gradle wrapper, choose
   *Use Gradle wrapper* — the wrapper is committed here
   (`gradle/wrapper/gradle-wrapper.jar`, pinned to Gradle 8.9). Android Studio
   supplies the Android SDK and writes `local.properties` automatically.
3. Pick a device or emulator and press **Run ▶**.

**Command line**

```bash
cd examples/guide
./gradlew :app:installDebug   # build + install onto a connected device/emulator
# or
./gradlew :app:assembleDebug  # just build the APK (app/build/outputs/apk/debug)
```

You'll need the Android SDK installed and `local.properties` pointing at it
(`sdk.dir=/path/to/Android/sdk`), or set `ANDROID_HOME`. Android Studio handles
this for you.

> If the wrapper jar is ever missing, regenerate it with
> `gradle wrapper --gradle-version 8.9` (any local Gradle), or just open the
> project in Android Studio and it will provision one.

## Next step: make the values live

1. Add the SDK to [`app/build.gradle.kts`](app/build.gradle.kts):

   ```kotlin
   implementation("ai.shipeasy:shipeasy-kotlin:<latest>")
   ```

2. Create a client once at startup and replace each
   `// TODO: once ai.shipeasy:shipeasy-kotlin is installed` block in
   [`Entities.kt`](app/src/main/java/ai/shipeasy/guide/Entities.kt) with the
   real call shown on that card, e.g.:

   ```kotlin
   val c = Shipeasy.client(/* serverKey = ... */)
   val on = c.getFlag("new_checkout", mapOf("user_id" to "u_123"))
   ```

3. Feed the returned values into the `Entity.value` / `Entity.meta` fields (or
   lift them into Compose state) and the cards go live.

Docs: https://docs.shipeasy.ai

## Version notes

This project is pinned to a mutually compatible toolchain trio:

| Tool                       | Version       |
| -------------------------- | ------------- |
| Android Gradle Plugin      | 8.5.2         |
| Kotlin                     | 2.0.20        |
| Kotlin Compose compiler    | `org.jetbrains.kotlin.plugin.compose` 2.0.20 (tracks Kotlin) |
| Compose BOM                | 2024.09.03    |
| Gradle (wrapper)           | 8.9           |
| compileSdk / targetSdk     | 34            |
| minSdk                     | 24            |
| JVM target                 | 17            |

With Kotlin 2.0, the Compose compiler is a standalone Gradle plugin whose
version is implied by the Kotlin plugin version — so there is no separate
`composeOptions { kotlinCompilerExtensionVersion }` block. Bump Kotlin and the
`plugin.compose` plugin together.
