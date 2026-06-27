# shipeasy (Kotlin)

Server SDK for [Shipeasy](https://shipeasy.dev). JVM/Android-compatible.

```kotlin
implementation("ai.shipeasy:shipeasy-kotlin:0.9.0")
```

📖 **Documentation:** [Installation & configuration](docs/pages/installation.md)
(Gradle/Maven coordinates, `configure()`, and Spring Boot / Ktor / Android
wiring) · [full docs](docs/)

## Quickstart — `configure()` once, then `Client(user)`

Configure the SDK once at app boot, then evaluate per user/request with a
lightweight `Client(user)`. The bound `Client` takes **no user argument** on its
methods — the user is bound at construction.

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

// Once, at boot. `attributes` maps YOUR user object into the targeting bag every
// evaluation reads; omit it when you already pass a plain attribute map.
configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
)

// Per request — cheap; delegates to the engine, no own connection/poll.
val flags = Client(currentUser)
flags.getFlag("new_checkout")                                   // → Boolean
flags.getConfig("billing_copy")
val r = flags.getExperiment("checkout_button", mapOf("color" to "blue"))
flags.getKillswitch("payments")
```

With no `attributes` transform, the user object IS the attribute map:

```kotlin
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
Client(mapOf("user_id" to "u_123", "plan" to "pro")).getFlag("new_checkout")
```

For a long-running server that should also poll for updates in the background,
start the engine returned by `configure`:

```kotlin
runBlocking { configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY")).init() }
```

## The `Engine` (advanced / direct use)

`configure()` builds and returns an `Engine` — the heavyweight client that owns
the HTTP connection, the cached blobs, the poll timer, overrides, telemetry, and
`see()` error reporting. You can also construct one directly when you need an
explicit instance (e.g. multiple keys, tests, or the per-call `user` form):

```kotlin
import ai.shipeasy.Engine

val engine = Engine(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
runBlocking { engine.init() }

engine.getFlag("new_checkout", mapOf("user_id" to "u_123"))     // per-call user arg
engine.getConfig("billing_copy")
val r = engine.getExperiment("checkout_button", mapOf("user_id" to "u_123"), mapOf("color" to "blue"))
engine.track("u_123", "purchase", mapOf("amount" to 49))
engine.close()
```

> **Renamed in 0.8.0 (BREAKING):** the heavyweight class formerly called `Client`
> is now `Engine`. The name `Client` is now the lightweight user-bound handle
> shown above. Replace `Client(apiKey = …)` with `Engine(apiKey = …)` (or switch
> to `configure(...)`).

## Server-side rendering (SSR)

Emit the request's evaluated flags as a declarative `<script>` tag so the
browser SDK has them on first paint. `bootstrapScriptTag` carries the payload in
`data-*` attributes (**no key**); the static `se-bootstrap.js` loader hydrates
`window.__SE_BOOTSTRAP` and writes the `__se_anon_id` cookie so the browser
buckets identically to the server.

```kotlin
val user = mapOf("user_id" to "u_123")

// Two tags for the document <head>. The PUBLIC client key (not the server
// key) goes on the i18n loader tag.
val head = c.bootstrapScriptTag(user, anonId = anonId) +
           c.i18nScriptTag(clientKey, "en:prod")

// …or get the raw payload ({flags, configs, experiments, killswitches}):
val boot = c.evaluate(user)
```

`bootstrapScriptTag` also accepts `i18nProfile` and `baseUrl`
(defaults to `https://cdn.shipeasy.ai`).

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
val c = Engine.fromSnapshot(
    flags = mapOf("gates" to /* … body of GET /sdk/flags */),
    experiments = mapOf("experiments" to /* … body of GET /sdk/experiments */),
)
c.getFlag("new_checkout", mapOf("user_id" to "u_123"))

// Or from a JSON file shaped like
//   { "flags": <GET /sdk/flags body>, "experiments": <GET /sdk/experiments body> }
val c2 = Engine.fromFile("/path/to/snapshot.json")
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
flag state. `Engine.forTesting()` returns a ready-to-use engine that does **zero
network**: telemetry is off, `init()`/`initOnce()` and `track()` are no-ops, and
no API key is required. Seed exactly the values your test needs with the
`override*` setters — an override always wins over fetched state, so the same
setters also work on a normal engine to force a value locally.

```kotlin
import ai.shipeasy.Engine

val c = Engine.forTesting()

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
Engine.forTesting().use { c ->
    c.overrideFlag("new_checkout", true)
    assertTrue(c.getFlag("new_checkout", emptyMap()))
}
```

To test code that uses the bound `Client(user)`, point the global engine at a
test/snapshot engine with `configure(...)` (or the offline factories) at setup,
then construct `Client(user)` as usual.
