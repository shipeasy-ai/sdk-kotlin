# Configuration

Configure the SDK **once** at app boot with `configure(...)`, then evaluate per
user/request with `Client(user)`.

## `configure(...)`

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
)
```

`configure()` builds the process-global `Engine` (HTTP client + blob cache +
poll), registers the `attributes` transform, and **returns the `Engine`**. The
**first call wins**; later calls return the existing engine and leave the
transform untouched.

### Full signature

```kotlin
fun configure(
    apiKey: String,                              // server key — authenticates flags/experiments
    attributes: AttributesFn? = null,            // your user object → attribute map
    baseUrl: String? = null,                     // default https://edge.shipeasy.dev
    env: String = "prod",                        // tags telemetry + see() events
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    privateAttributes: List<String> = emptyList(),
    stickyStore: StickyBucketStore? = null,
): Engine
```

> **Use the SERVER key.** It authenticates flag, experiment and SSR evaluation
> and must never reach the browser. The public *client* key is only used by the
> i18n loader / bootstrap script tags (see [Advanced](advanced.md) / [i18n](i18n.md)).

## The `attributes` transform

`attributes: (Any?) -> Map<String, Any?>` maps YOUR user object into the
targeting bag every evaluation reads (`user_id`, `anonymous_id`, plus targeting
attributes). It runs **once per `Client(user)` construction**.

With **no** transform, the identity default is used — if the user object is
already a `Map`, it IS the attribute bag:

```kotlin
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
Client(mapOf("user_id" to "u_123", "plan" to "pro")).getFlag("new_checkout")
```

## Identity / anonymous default

When the bound attributes carry neither `user_id` nor `anonymous_id`, the SDK
defaults `anonymous_id` to the request-scoped `__se_anon_id` cookie (resolved by
`AnonIdFilter`, see [Advanced](advanced.md)). An explicit unit always wins.

## init/poll vs one-shot

`configure()` kicks off a **fire-and-forget one-shot fetch** (`Engine.initOnce()`)
so the first `Client(user).getFlag(...)` resolves against real rules without any
explicit `init()`.

For a long-running server that should also **poll** for updates in the
background, start the returned engine:

```kotlin
runBlocking { configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY")).init() }
```

`init()` does the first fetch then starts a background poll (interval driven by
the server's `X-Poll-Interval` header, default 30s). `initOnce()` fetches once
and never polls. Both are `suspend`.

## Direct `Engine` construction

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

## Environment variables

The SDK reads no env vars implicitly — pass `apiKey` (and any `baseUrl`)
explicitly. By convention the key lives in `SHIPEASY_SERVER_KEY`.
