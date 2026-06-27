---
name: shipeasy-kotlin
description: Use Shipeasy (feature flags, configs, kill switches, A/B experiments, i18n) from Kotlin/JVM. Covers configure() + Client(user), getFlag/getConfig/getExperiment/getKillswitch, track, see() error reporting, testing, and OpenFeature availability.
---

# Shipeasy Kotlin SDK

Server-side SDK for the JVM (Android-compatible). Evaluates flags, configs, kill
switches and experiments **locally** against rule blobs fetched from the edge.

## Install

```kotlin
implementation("ai.shipeasy:shipeasy-kotlin:0.8.0")   // JDK 17+
```

## Configure once, evaluate per user

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

// Once at boot. `attributes` maps your user object → the targeting bag.
configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),               // SERVER key
    attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
)

// Per request — cheap, user bound at construction (methods take no user arg).
val flags = Client(currentUser)
flags.getFlag("new_checkout")                       // → Boolean (default=false unless 2nd arg)
flags.getConfig("billing_copy", default = "Pay")    // → Any?
flags.getKillswitch("payments")                     // → true means killed
val r = flags.getExperiment("checkout_button", mapOf("color" to "blue"))
```

With no `attributes` transform, a `Map` user IS the attribute bag:
`Client(mapOf("user_id" to "u_123", "plan" to "pro")).getFlag("new_checkout")`.

For background polling on a long-running server:
`runBlocking { configure(apiKey = key).init() }`.

## Engine (advanced / per-call user)

`configure()` returns the process-global heavyweight `Engine`. Construct directly
for multiple keys, tests, or the per-call user form:

```kotlin
import ai.shipeasy.Engine
val engine = Engine(apiKey = key)
runBlocking { engine.init() }
engine.getFlag("new_checkout", mapOf("user_id" to "u_123"))
engine.track("u_123", "purchase", mapOf("amount" to 49))   // experiment conversion
engine.logExposure("u_123", "checkout_button")             // manual exposure
engine.close()
```

`getFlagDetail(name)` → `FlagDetail(value, reason)` (reason: OVERRIDE,
CLIENT_NOT_READY, FLAG_NOT_FOUND, OFF, RULE_MATCH, DEFAULT).

## Error reporting — see()

```kotlin
import ai.shipeasy.see
try { chargeCard(order) }
catch (e: Exception) {
    see(e).causesThe("checkout").extras(mapOf("order_id" to order.id)).to("use backup processor")
}
```

`to(outcome)` is the terminal — without it nothing is sent. `seeViolation(name)`
for non-exceptions; `controlFlowException(e).because("...")` to mark expected
(reports nothing).

## Testing (no network)

```kotlin
import ai.shipeasy.Engine
val c = Engine.forTesting()
c.overrideFlag("new_checkout", true)
c.getFlag("new_checkout", mapOf("user_id" to "u_123"))   // → true
// also: overrideConfig, overrideExperiment(name, group, params), clearOverrides()
// offline against real blobs: Engine.fromSnapshot(flags, experiments) / Engine.fromFile(path)
```

## Notes

- **i18n:** server SDK has no `t()`. Emit `engine.i18nScriptTag(clientKey, profile)`
  (public client key) for SSR; rendering happens in the browser client SDK.
- **OpenFeature:** no provider bundled — use `getFlag` / `getFlagDetail` directly.
- **Advanced:** `privateAttributes`, `stickyStore = InMemoryStickyStore()`,
  `AnonIdFilter` (servlet) for logged-out bucketing, `onChange`, `bootstrapScriptTag` SSR.
