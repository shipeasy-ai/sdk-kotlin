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

## Default values

`getFlag` and `getConfig` take an optional default. The flag default is returned
**only** when the gate cannot be evaluated — the client isn't initialized yet, or
the flag isn't in the loaded blob — and **never** for a flag that legitimately
evaluates to `false`. The existing two-argument call stays valid.

```kotlin
// returns `true` only if the client isn't ready / the flag is unknown;
// a known flag that evaluates false still returns false
c.getFlag("new_checkout", mapOf("user_id" to "u_123"), default = true)

// returns "Pay now" if the config key is absent
c.getConfig("billing_copy", default = "Pay now")
```

## Evaluation detail

`getFlagDetail` returns the value plus a `reason` explaining it (LaunchDarkly
`variationDetail` parity). The reason is computed at the SDK boundary; the
canonical evaluation is untouched.

```kotlin
val d: FlagDetail = c.getFlagDetail("new_checkout", mapOf("user_id" to "u_123"))
d.value   // Boolean
d.reason  // one of the Reason constants
```

`reason` is one of:

| `Reason` constant  | Meaning                                              |
| ------------------ | ---------------------------------------------------- |
| `OVERRIDE`         | A local override supplied the value (no telemetry).  |
| `CLIENT_NOT_READY` | Client not initialized — no rules blob loaded yet.   |
| `FLAG_NOT_FOUND`   | The gate name isn't present in the loaded blob.      |
| `OFF`              | Gate present but disabled / killed.                  |
| `RULE_MATCH`       | The gate evaluated `true` (rules + rollout passed).  |
| `DEFAULT`          | The gate evaluated `false`.                          |

`getFlag` is implemented on top of `getFlagDetail` and returns `.value`.

## Change listeners

`onChange` subscribes to data-change notifications. The listener fires after a
background poll brings **new** data (HTTP 200, not 304) — not for the initial
`init()` fetch, and never in an offline/test client (no polling). It returns an
unsubscribe function.

```kotlin
val unsubscribe = c.onChange {
    // rebuild any cached evaluations, warm a downstream cache, etc.
}
// later
unsubscribe()
```

Each listener is invoked in a try/catch; a throwing listener is logged and does
not affect the others.

## Offline snapshot

Run fully offline against a pre-captured snapshot — no network ever. Like
`forTesting()` this uses the local-mode plumbing (`init()`/`initOnce()`/`track()`
are no-ops, telemetry off), but it seeds the **real** flags + experiments blobs
so evaluations run the canonical eval against the snapshot. Local overrides still
apply on top.

```kotlin
// From the two wire bodies directly:
val c = Client.fromSnapshot(
    flags = mapOf("gates" to /* … body of GET /sdk/flags */),
    experiments = mapOf("experiments" to /* … body of GET /sdk/experiments */),
)
c.getFlag("new_checkout", mapOf("user_id" to "u_123"))

// Or from a JSON file shaped like
//   { "flags": <GET /sdk/flags body>, "experiments": <GET /sdk/experiments body> }
val c2 = Client.fromFile("/path/to/snapshot.json")
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
