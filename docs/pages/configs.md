# Dynamic configs

`getConfig(name)` returns the typed value of a dynamic config — a JSON value
(`String`, `Number`, `Boolean`, `Map`, `List`, or `null`) managed in the
dashboard.

## Bound `Client` form

```kotlin
val flags = Client(currentUser)
val copy = flags.getConfig("billing_copy")   // → Any?
```

## With a default

`default` (defaults to `null`) is returned when the config key is absent — no
override and not present in the loaded blob. A `null` override is honoured and
wins over the default.

```kotlin
// returns "Pay now" if the config key is absent
flags.getConfig("billing_copy", default = "Pay now")
```

## Low-level `Engine` form

`getConfig` on the engine is **not user-scoped** (configs carry their value in
the blob; targeting is resolved server-side at publish):

```kotlin
engine.getConfig("billing_copy")
engine.getConfig("billing_copy", default = "Pay now")
```

## Typing the result

The value is `Any?`. Cast as needed:

```kotlin
val copy = flags.getConfig("billing_copy") as? String ?: "Pay now"

@Suppress("UNCHECKED_CAST")
val limits = flags.getConfig("rate_limits") as? Map<String, Any?> ?: emptyMap()
```
