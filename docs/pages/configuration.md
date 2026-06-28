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

`configure()` builds the process-global SDK state (HTTP client + blob cache +
optional poll) and registers the `attributes` transform. The **first call
wins**; later calls are ignored and leave the transform untouched, so configure
exactly once.

### Options

| Parameter           | Default                       | What it does |
| ------------------- | ----------------------------- | ------------ |
| `apiKey`            | —                             | **SERVER** key — authenticates flags/experiments/SSR. |
| `attributes`        | identity                      | Your user object → attribute map. Runs once per `Client(user)`. |
| `baseUrl`           | `https://edge.shipeasy.dev`   | Edge API origin override. |
| `env`               | `"prod"`                      | Tags telemetry + `see()` events. |
| `disableTelemetry`  | `false`                       | Opt out of per-eval usage telemetry. |
| `telemetryUrl`      | `null`                        | Override the telemetry beacon origin. |
| `privateAttributes` | `[]`                          | Attrs usable for targeting but stripped from outbound payloads. |
| `stickyStore`       | `null`                        | Lock a unit to its first-assigned variant. |
| `poll`              | `false`                       | `true` → fetch once and keep polling; `false` → one-shot fetch. |

The full options table with types lives on the [Installation](installation.md)
page — that page is the canonical home for `configure()`.

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

## One-shot vs polling

By default `configure()` fetches the rule blob **once** so the first
`Client(user).getFlag(...)` resolves against real rules. For a long-running
server that should also **poll** for updates in the background, pass
`poll = true`:

```kotlin
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"), poll = true)
```

With `poll = true` the SDK does the first fetch then refreshes in the background
(interval driven by the server's `X-Poll-Interval` header, default 30s).
Register an [`onChange`](advanced.md) listener to react to each refresh.

## Environment variables

The SDK reads no env vars implicitly — pass `apiKey` (and any `baseUrl`)
explicitly. By convention the key lives in `SHIPEASY_SERVER_KEY`.
