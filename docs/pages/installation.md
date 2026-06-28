# Installation & configuration

Server SDK for the JVM (Android-compatible). Distributed on Maven Central as
`ai.shipeasy:shipeasy-kotlin`.

## Coordinates

### Gradle (Kotlin DSL) — `build.gradle.kts`

```kotlin
implementation("ai.shipeasy:shipeasy-kotlin:0.10.0")
```

### Gradle (Groovy DSL) — `build.gradle`

```groovy
implementation 'ai.shipeasy:shipeasy-kotlin:0.10.0'
```

### Maven — `pom.xml`

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy-kotlin</artifactId>
  <version>0.10.0</version>
</dependency>
```

## Runtime

- **JDK 17+** (uses `java.net.http.HttpClient`). Android: minSdk 26+.
- Kotlin coroutines are used internally. You never call `init()` yourself —
  `configure()` owns the fetch lifecycle.
- `jakarta.servlet-api` is a **`compileOnly`** dependency used by the optional
  `AnonIdFilter`; your servlet container supplies it at runtime, so it adds
  nothing to non-servlet (Ktor, Android, http4k) deployments.

## Imports

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client
import ai.shipeasy.see           // structured error reporting
```

---

## Configure once, then bind per user

Configure the SDK **once** at app boot with `configure(...)`, then evaluate per
user/request with a lightweight `Client(user)`. This page is the canonical home
for `configure()` — every snippet elsewhere assumes it already ran.

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
)

val flags = Client(currentUser)
if (flags.getFlag("new_checkout")) { /* … */ }
```

The **first call wins**; later `configure()` calls are ignored, so configure
exactly once. `configure()` kicks off a one-shot fetch in the background, so the
first `Client(user).getFlag(...)` resolves against real rules without any extra
call. For a long-running server that should also **poll** for updates, pass
`poll = true` (see below).

### `configure(...)` options

| Parameter           | Type                   | Default                       | What it does |
| ------------------- | ---------------------- | ----------------------------- | ------------ |
| `apiKey`            | `String`               | —                             | **SERVER** key — authenticates flags/experiments/SSR. Never reaches the browser. |
| `attributes`        | `(Any?) -> Map<…>`     | identity                      | Maps YOUR user object → the targeting bag (`user_id`, `anonymous_id`, attrs). Runs once per `Client(user)`. |
| `baseUrl`           | `String?`              | `https://edge.shipeasy.dev`   | Edge API origin override. |
| `env`               | `String`               | `"prod"`                      | Tags telemetry + `see()` events. |
| `disableTelemetry`  | `Boolean`              | `false`                       | Opt out of per-eval usage telemetry. |
| `telemetryUrl`      | `String?`              | `null`                        | Override the telemetry beacon origin. |
| `privateAttributes` | `List<String>`         | `[]`                          | Attrs usable for targeting but stripped from outbound `track()` / `see()` extras. |
| `stickyStore`       | `StickyBucketStore?`   | `null`                        | Lock a unit to its first-assigned variant (see [Advanced](advanced.md)). |
| `poll`              | `Boolean`              | `false`                       | `true` → fetch once **and** keep polling in the background; `false` → one-shot fetch only. |

> **Use the SERVER key.** It authenticates flag, experiment and SSR evaluation
> and must never reach the browser. The public *client* key is only used by the
> i18n loader / bootstrap script tags (see [i18n](i18n.md)).

### The `attributes` transform

`attributes: (Any?) -> Map<String, Any?>` maps YOUR user object into the
targeting bag every evaluation reads (`user_id`, `anonymous_id`, plus targeting
attributes). It runs **once per `Client(user)` construction**.

```kotlin
configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
)
```

With **no** transform, the identity default is used — if the user object is
already a `Map`, it IS the attribute bag:

```kotlin
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
Client(mapOf("user_id" to "u_123", "plan" to "pro")).getFlag("new_checkout")
```

### Identity / anonymous default

When the bound attributes carry neither `user_id` nor `anonymous_id`, the SDK
defaults `anonymous_id` to the request-scoped `__se_anon_id` cookie (resolved by
`AnonIdFilter`, see [Advanced](advanced.md)). An explicit unit always wins.

### Background polling

By default `configure()` fetches the rule blob **once**. For a long-running
server that should also pick up flag changes without a restart, pass
`poll = true`:

```kotlin
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"), poll = true)
```

With `poll = true` the SDK does the first fetch then refreshes in the background
(interval driven by the server's `X-Poll-Interval` header, default 30s). Register
an [`onChange`](advanced.md) listener to react to each refresh.

### Environment variables

The SDK reads no env vars implicitly — pass `apiKey` (and any `baseUrl`)
explicitly. By convention the key lives in `SHIPEASY_SERVER_KEY`.

---

## Framework wiring

`configure()` once at startup; construct `Client(user)` per request. The
`AnonIdFilter` (servlet `Filter`) mints the shared `__se_anon_id` cookie so
logged-out traffic buckets identically on server and browser — wire it where
your framework registers filters.

### Spring Boot

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client
import ai.shipeasy.AnonIdFilter
import jakarta.annotation.PostConstruct
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

@Component
class ShipeasyConfig {
    @PostConstruct
    fun init() {
        configure(
            apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
            attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
            poll = true,   // long-running server → keep polling
        )
    }

    // logged-out traffic gets a stable anon id → consistent bucketing
    @Bean
    fun shipeasyAnonId() = FilterRegistrationBean(AnonIdFilter())
}

// In a controller, per request:
@GetMapping("/checkout")
fun checkout(@AuthenticationPrincipal user: MyUser): String {
    val flags = Client(user)                 // bind once per request
    return if (flags.getFlag("new_checkout")) "new" else "old"
}
```

### Ktor

Ktor isn't servlet-based, so use the `AnonId` primitives instead of the filter:
read/mint `__se_anon_id` in a plugin and stash it for the request.

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client
import io.ktor.server.application.*

fun Application.module() {
    // Once, at startup
    configure(
        apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
        attributes = { u -> mapOf("user_id" to (u as MyUser).id) },
        poll = true,
    )

    routing {
        get("/checkout") {
            val flags = Client(currentUser())   // bind once per request
            call.respondText(if (flags.getFlag("new_checkout")) "new" else "old")
        }
    }
}
```

### Plain `main()` / batch job

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

fun main() {
    // poll = true so a long-running job keeps refreshing
    configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"), poll = true)

    val flags = Client(mapOf("user_id" to "u_123", "plan" to "pro"))
    if (flags.getFlag("new_checkout")) { /* … */ }
}
```

### Android

Configure once in `Application.onCreate()`; bind a `Client` wherever you have the
signed-in user. There is no request scope, so pass the user explicitly.

```kotlin
import android.app.Application
import ai.shipeasy.configure
import ai.shipeasy.Client

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        configure(
            apiKey = BuildConfig.SHIPEASY_SERVER_KEY,
            attributes = { u -> mapOf("user_id" to (u as Account).id) },
            poll = true,
        )
    }
}

// Anywhere with the current user:
val flags = Client(currentAccount)
if (flags.getFlag("new_checkout")) showNewCheckout()
```

> On a mobile client, treat the embedded key as **public** — use a public client
> key, not a privileged server key.
