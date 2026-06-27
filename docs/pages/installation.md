# Installation & configuration

Server SDK for the JVM (Android-compatible). Distributed on Maven Central as
`ai.shipeasy:shipeasy-kotlin`.

## Coordinates

### Gradle (Kotlin DSL) — `build.gradle.kts`

```kotlin
dependencies {
    implementation("ai.shipeasy:shipeasy-kotlin:0.9.0")
}
```

### Gradle (Groovy DSL) — `build.gradle`

```groovy
dependencies {
    implementation 'ai.shipeasy:shipeasy-kotlin:0.9.0'
}
```

### Maven — `pom.xml`

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy-kotlin</artifactId>
  <version>0.9.0</version>
</dependency>
```

## Runtime

- **JDK 17+** (uses `java.net.http.HttpClient`). Android: minSdk 26+.
- Kotlin coroutines are used internally; `init()` is a `suspend` function — call
  it from a coroutine or wrap it in `runBlocking { }`.
- `jakarta.servlet-api` is a **`compileOnly`** dependency used by the optional
  `AnonIdFilter`; your servlet container supplies it at runtime, so it adds
  nothing to non-servlet (Ktor, Android, http4k) deployments.

## Imports

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client
import ai.shipeasy.Engine        // advanced / direct use, offline factories
import ai.shipeasy.see           // structured error reporting
```

---

## Configure once, then bind per user

Configure the SDK **once** at app boot with `configure(...)`, then evaluate per
user/request with a lightweight `Client(user)`. This page is the canonical home
for `configure()` — every snippet elsewhere assumes it already ran.

### `configure(...)` — full signature

```kotlin
fun configure(
    apiKey: String,                              // SERVER key — authenticates flags/experiments; never reaches the browser
    attributes: AttributesFn? = null,            // your user object → attribute map (runs once per Client(user))
    baseUrl: String? = null,                     // edge API origin; default https://edge.shipeasy.dev
    env: String = "prod",                        // tags telemetry + see() events
    disableTelemetry: Boolean = false,           // opt out of per-eval usage telemetry
    telemetryUrl: String? = null,                // override the telemetry beacon origin
    privateAttributes: List<String> = emptyList(), // attrs usable for targeting but stripped from outbound track()
    stickyStore: StickyBucketStore? = null,      // lock a unit to its first-assigned variant
): Engine
```

`configure()` builds the process-global `Engine` (HTTP client + blob cache +
poll), registers the `attributes` transform, kicks off a fire-and-forget
one-shot fetch, and **returns the `Engine`**. The **first call wins**; later
calls return the existing engine and leave the transform untouched.

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

### init/poll vs one-shot

`configure()` kicks off a **fire-and-forget one-shot fetch**
(`Engine.initOnce()`) so the first `Client(user).getFlag(...)` resolves against
real rules without any explicit `init()`. For a long-running server that should
also **poll** for updates in the background, start the returned engine:

```kotlin
runBlocking { configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY")).init() }
```

`init()` does the first fetch then starts a background poll (interval driven by
the server's `X-Poll-Interval` header, default 30s). `initOnce()` fetches once
and never polls. Both are `suspend`.

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
    )

    routing {
        get("/checkout") {
            val flags = Client(currentUser())   // bind once per request
            call.respondText(if (flags.getFlag("new_checkout")) "new" else "old")
        }
    }
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
        )
    }
}

// Anywhere with the current user:
val flags = Client(currentAccount)
if (flags.getFlag("new_checkout")) showNewCheckout()
```

> On a mobile client, treat the embedded key as **public** — use a public client
> key, not a privileged server key.

### Plain `main()` / batch job

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // init() (not just initOnce()) so a long-running job keeps polling
    configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY")).init()

    val flags = Client(mapOf("user_id" to "u_123", "plan" to "pro"))
    if (flags.getFlag("new_checkout")) { /* … */ }
}
```

---

## Direct `Engine` construction (advanced)

For multiple keys, explicit instances or the per-call `user` form:

```kotlin
import ai.shipeasy.Engine

val engine = Engine(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
runBlocking { engine.init() }

engine.getFlag("new_checkout", mapOf("user_id" to "u_123"))   // per-call user arg
engine.close()
```

The last-constructed `Engine` also becomes the default backing the package-level
`see()` functions. `Engine` is `AutoCloseable` — call `close()` (or `use { }`)
to stop the poll.
