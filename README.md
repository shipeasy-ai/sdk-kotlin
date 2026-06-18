# shipeasy (Kotlin)

Server SDK for [Shipeasy](https://shipeasy.dev). JVM/Android-compatible.

```kotlin
implementation("ai.shipeasy:shipeasy-kotlin:0.3.0")
```

```kotlin
import ai.shipeasy.Client

val c = Client(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
runBlocking { c.init() }

c.getFlag("new_checkout", mapOf("user_id" to "u_123"))
c.getConfig("billing_copy")
val r = c.getExperiment("checkout_button", mapOf("user_id" to "u_123"), mapOf("color" to "blue"))
c.track("u_123", "purchase", mapOf("amount" to 49))
c.close()
```

## Anonymous visitors (zero-config bucketing)

For logged-out traffic you need a *stable* unit so a fractional rollout buckets
the same on the server and in the browser. `AnonIdFilter` is a servlet `Filter`
that mints the shared `__se_anon_id` first-party cookie (used by every Shipeasy
SDK, incl. the browser) for any request without one; evaluations then **default
to it** as `anonymous_id`, so a logged-out request needs no per-call wiring.

```kotlin
// Spring Boot — a default FilterRegistrationBean maps to all paths
@Bean
fun shipeasyAnonId() = FilterRegistrationBean(AnonIdFilter())
```

```kotlin
// logged-out request → buckets on the __se_anon_id cookie automatically
c.getFlag("new_checkout", emptyMap())
```

`jakarta.servlet-api` is a `compileOnly` dependency — your container already
supplies it, so this adds nothing to your deployment. Non-servlet stacks (Ktor,
http4k, Javalin) can use the `AnonId` primitives directly. An explicit
`user_id`/`anonymous_id` always wins. The cookie is non-`HttpOnly` by design so
the browser SDK buckets identically; a request with **no** unit still resolves a
fully-rolled (100%) gate as on. Cookie name + format are a cross-SDK contract —
see `18-identity-bucketing.md`.

## Testing

In unit tests you usually don't want the SDK to hit the network or read live
flag state. `Client.forTesting()` returns a ready-to-use client that does **zero
network**: telemetry is off, `init()`/`initOnce()` and `track()` are no-ops, and
no API key is required. Seed exactly the values your test needs with the
`override*` setters — an override always wins over fetched state, so the same
setters also work on a normal client to force a value locally.

```kotlin
import ai.shipeasy.Client

val c = Client.forTesting()

// Flags
c.overrideFlag("new_checkout", true)
c.getFlag("new_checkout", mapOf("user_id" to "u_123"))   // → true

// Configs (value may be any type, including null)
c.overrideConfig("billing_copy", "Pay now")
c.getConfig("billing_copy")                              // → "Pay now"

// Experiments — getExperiment returns inExperiment=true with your group/params
c.overrideExperiment("checkout_button", group = "treatment", params = mapOf("color" to "green"))
val r = c.getExperiment("checkout_button", mapOf("user_id" to "u_123"), defaultParams = null)
r.inExperiment  // true
r.group         // "treatment"
r.params        // {color=green}

// track() is a no-op here — no key, no network, never throws
c.track("u_123", "purchase", mapOf("amount" to 49))

// Reset between cases
c.clearOverrides()
```

Entities you don't override fall back to their defaults: a flag reads `false`, a
config reads `null`, and an experiment reads not-in-experiment. The client is
`AutoCloseable`, so wrap it in `use { }` to clean up after the test:

```kotlin
Client.forTesting().use { c ->
    c.overrideFlag("new_checkout", true)
    assertTrue(c.getFlag("new_checkout", emptyMap()))
}
```
