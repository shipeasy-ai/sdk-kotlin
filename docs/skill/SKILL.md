---
name: shipeasy-kotlin
description: Use Shipeasy (feature flags, configs, kill switches, A/B experiments, i18n) from Kotlin/JVM. Covers configure() + Client(user) on servers, ShipeasyClient + configureAndroid for shipped Android apps, getFlag/getConfig/universe(name).assign()/getKillswitch, track, see() error reporting, testing.
---

# Shipeasy Kotlin SDK

Two front doors on the JVM. **`configure()` + `Client(user)`** is the server SDK
(server key, evaluates rules locally — never embed a server key in a shipped
app). **`configureAndroid()`/`configureClient()` + `ShipeasyClient`** is the
native client for shipped Android apps (public client key, server-side eval over
`/sdk/evaluate`, persisted device anon id).

> The documented surface is exactly **`configure()`** (setup) and the bound
> **`Client(user)`** (use), plus the package-level helpers below. For deeper docs,
> fetch any page/snippet from the manifest at
> <https://shipeasy-ai.github.io/sdk-kotlin/manifest.json> (raw URLs below).

## Native Android app? ShipeasyClient (client key)

```kotlin
// build.gradle.kts — Android companion artifact (thin adapter over the core jar):
implementation("ai.shipeasy:shipeasy-kotlin-android:0.14.0")
```

```kotlin
import ai.shipeasy.android.configureAndroid
import ai.shipeasy.shipeasyClient

// Once, in Application.onCreate — PUBLIC client key (pk_…), safe to embed.
// Persists the device anonymous_id across launches (stable bucketing on cold start).
configureAndroid(this, clientKey = BuildConfig.SHIPEASY_CLIENT_KEY)

// From a coroutine (identify is a suspend fun: /sdk/evaluate round-trip + cache):
shipeasyClient()?.identify(mapOf("user_id" to userId, "plan" to "pro"))
val on = shipeasyClient()?.getFlag("new_checkout") ?: false     // cheap cache read
val cta = shipeasyClient()?.universe("hero_cta")?.assign()      // Assignment (auto-logs exposure)
shipeasyClient()?.track("purchase", mapOf("amount" to 49))
shipeasyClient()?.reset()   // logout: keep device anon id, drop user_id
```

Non-Android JVM client, or custom persistence (EncryptedSharedPreferences /
DataStore): `configureClient(clientKey, store = <your AnonStore>)`. Reference:
<https://shipeasy-ai.github.io/sdk-kotlin/pages/installation.md>

The rest of this skill covers the **server** SDK (`configure()` + `Client`).

## Install

```kotlin
implementation("ai.shipeasy:shipeasy-kotlin:0.10.0")
```

## Configure once, evaluate per user

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

// Once at boot. `attributes` maps your user object → the targeting bag.
configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),               // SERVER key
    attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
    // poll = true,  // long-running server: keep flags fresh with a background poll
)

// Per request — cheap, user bound at construction (methods take no user arg).
val flags = Client(currentUser)                     // construct once per callsite
flags.getFlag("new_checkout")                       // → Boolean (default=false unless 2nd arg)
flags.getConfig("billing_copy", default = "Pay")    // → Any?
flags.getKillswitch("payments")                     // → true means killed
// Named switch: getKillswitch(name, switchKey) — an unconfigured key falls back
// to the kill switch's top-level value.
```

`configure()` is first-config-wins and owns the fetch lifecycle (one-shot by
default; `poll = true` for a background refresh — you never call `init()`).
Reference: <https://shipeasy-ai.github.io/sdk-kotlin/pages/configuration.md> ·
<https://shipeasy-ai.github.io/sdk-kotlin/pages/flags.md>

## Experiments + track (Client-only, end to end)

Experiments are read by UNIVERSE (a mutual-exclusion pool — the unit lands in ≤1
experiment). `assign()` picks it and resolves params; it's side-effect free. The
exposure fires on the first `get()` read (deduped per process and durably per
`(unit, experiment, group)`); use `peek()` to read a param without logging one.

```kotlin
val flags = Client(currentUser)                     // construct once per callsite
val cta = flags.universe("hero_cta").assign()       // Assignment (no exposure yet)
// cta.name: String? (experiment), cta.group: String?, cta.enrolled: Boolean
// cta.get(field, fallback):  variant override ?? universe default ?? fallback (logs exposure on first read)
// cta.peek(field, fallback): same value, read-only (never logs an exposure)

render(cta.get("primary_label", "Sign up"))         // always safe — first read logs one exposure when enrolled
flags.track("purchase", mapOf("amount" to 49))      // conversion for the bound user
```

Reference: <https://shipeasy-ai.github.io/sdk-kotlin/pages/experiments.md> · track
snippet <https://shipeasy-ai.github.io/sdk-kotlin/snippets/metrics/track.md>

## Error reporting — see()

```kotlin
import ai.shipeasy.see
try { chargeCard(order) }
catch (e: Exception) {
    see(e).causesThe("checkout").to("use backup processor", mapOf("order_id" to order.id))
}
```

`to(outcome, map)` is the terminal — without it nothing is sent; extras ride the
terminal inline. Never `causesThe(x).extras(map).to(y)`: it splits the
consequence sentence in half. `to` returns `Unit`, so extras cannot trail it.
`seeViolation(name)` for non-exceptions;
`controlFlowException(e).because("...")` to mark expected (reports nothing).
Reference:
<https://shipeasy-ai.github.io/sdk-kotlin/pages/error-reporting.md> · snippet
<https://shipeasy-ai.github.io/sdk-kotlin/snippets/ops/see.md>

## Testing (no network)

```kotlin
import ai.shipeasy.configureForTesting
import ai.shipeasy.configureForOffline
import ai.shipeasy.overrideFlag
import ai.shipeasy.clearOverrides
import ai.shipeasy.Client

// Seed values up front; reads go through the ordinary Client(user). Replaces
// prior config, so each test can reconfigure freely.
configureForTesting(
    flags = mapOf("new_checkout" to true),
    configs = mapOf("billing_copy" to "50% off"),
    experiments = mapOf("checkout_button" to ("treatment" to mapOf("color" to "green"))),
)
val flags = Client(mapOf("user_id" to "u_1"))
flags.getFlag("new_checkout")                       // true

overrideFlag("new_checkout", false)                 // flip on the spot
clearOverrides()                                    // drop every override (incl. the seed)

// Offline: evaluate the REAL rules from a snapshot or JSON file, no network.
configureForOffline(path = "shipeasy-snapshot.json")
```

Reference: <https://shipeasy-ai.github.io/sdk-kotlin/pages/testing.md>

## Notes

- **i18n:** the server SDK has no `t()`. Emit the loader tag via package-level
  `i18nScriptTag()` (public client key) for SSR — every argument is optional and
  falls back to `configure(clientKey = ..., profile = ...)`; rendering happens in
  the browser client SDK. Reference:
  <https://shipeasy-ai.github.io/sdk-kotlin/pages/i18n.md>
- **OpenFeature:** no provider bundled — use `getFlag` / `getFlagDetail` directly.
  Reference: <https://shipeasy-ai.github.io/sdk-kotlin/pages/openfeature.md>
- **Advanced:** `privateAttributes`, `stickyStore = InMemoryStickyStore()`,
  `AnonIdFilter` (servlet) for logged-out bucketing, package-level `onChange`
  (requires `poll = true`), `bootstrapScriptTag` SSR (carries the identified
  user as `data-user` so the browser adopts identity on first paint — no
  anon→identified flip), `devtoolsScriptTag()` (hosted overlay: Shift+Alt+S or
  `?se=1`; needs `projectId` + `clientKey` on `configure`). Reference:
  <https://shipeasy-ai.github.io/sdk-kotlin/pages/advanced.md>
- **Quiet outside production (0.16.0+):** the SDK is offline by default when the
  environment isn't production — no fetch, `track`, `see()`, or telemetry until
  you opt in. It's production when the `shipeasy.env` system property or a
  `SHIPEASY_ENV`/`APP_ENV`/`ENV` var is `prod`/`production`, else it falls back to
  the `env` option (defaults to `"prod"`). Force it with
  `configure(..., isNetworkEnabled = true)` or `-Dshipeasy.env=production`.
  Reference: <https://shipeasy-ai.github.io/sdk-kotlin/pages/configuration.md>
