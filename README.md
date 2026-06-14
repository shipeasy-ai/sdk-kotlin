# shipeasy (Kotlin)

Server SDK for [Shipeasy](https://shipeasy.dev). JVM/Android-compatible.

```kotlin
implementation("ai.shipeasy:shipeasy:0.1.0")
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
