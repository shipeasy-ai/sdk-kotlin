# Testing

Two offline factories let you evaluate without ever touching the network.

## `Engine.forTesting()` — overrides only

Returns a ready-to-use engine that does **zero network**: telemetry off,
`init()` / `initOnce()` / `track()` are no-ops, no API key required. Seed exactly
the values your test needs with the `override*` setters — an override always wins
over fetched state.

```kotlin
import ai.shipeasy.Engine

val c = Engine.forTesting()

// Flags
c.overrideFlag("new_checkout", true)
c.getFlag("new_checkout", mapOf("user_id" to "u_123"))   // → true

// Configs (value may be any type, including null)
c.overrideConfig("billing_copy", "Pay now")
c.getConfig("billing_copy")                              // → "Pay now"

// Experiments → inExperiment=true with your group/params
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

Entities you don't override fall back to defaults: a flag reads `false`, a config
reads `null`, an experiment reads not-in-experiment.

`Engine` is `AutoCloseable` — wrap it in `use { }`:

```kotlin
Engine.forTesting().use { c ->
    c.overrideFlag("new_checkout", true)
    assertTrue(c.getFlag("new_checkout", emptyMap()))
}
```

## Offline snapshots — `fromSnapshot` / `fromFile`

Run fully offline against a **real** captured blob so evaluations run the
canonical eval (rules + rollout), not just overrides. `init()`/`track()` are
still no-ops; local overrides still apply on top.

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

## Testing code that uses `Client(user)`

Point the global engine at a test/snapshot engine at setup, then construct
`Client(user)` as usual. Use the internal install seam (or `configure(...)` with
a stub `baseUrl`) — in tests the simplest path is `configure(...)` against a
mock, or install an offline engine and read it via `Client`.
