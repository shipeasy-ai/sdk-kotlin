# Testing

Two `configure()` siblings let your tests evaluate without ever touching the
network — both REPLACE any prior configuration (unlike `configure()`'s
first-call-wins), so a suite can reconfigure freely between cases. You read
results through the ordinary `Client(user)` and seed values with the top-level
override helpers.

## `configureForTesting(...)` — seed values, zero network

Drop-in sibling of `configure()` with **no network, ever** (no api key needed).
Seed `flags`, `configs`, and `experiments` inline, then read them through a
normal `Client(user)`:

```kotlin
import ai.shipeasy.configureForTesting
import ai.shipeasy.Client

configureForTesting(
    flags = mapOf("new_checkout" to true),                    // name to Boolean
    configs = mapOf("billing_copy" to "Pay now"),             // name to value
    experiments = mapOf(                                      // name to (group to params)
        "checkout_button" to ("treatment" to mapOf("color" to "green")),
    ),
)

val flags = Client(mapOf("user_id" to "u_123"))
flags.getFlag("new_checkout")                 // → true
flags.getConfig("billing_copy")               // → "Pay now"

val r = flags.getExperiment("checkout_button", defaultParams = null)
r.inExperiment   // true
r.group          // "treatment"
r.params         // {color=green}
```

Seed shapes:

- `flags` — `mapOf(name to bool)`
- `configs` — `mapOf(name to value)` (any type, including `null`)
- `experiments` — `mapOf(name to (group to params))` (the value is a Kotlin
  `Pair`, `group to params`)

`track()` / `logExposure()` are no-ops here — no key, no network, never throw.
Entities you don't seed fall back to defaults: a flag reads `false`, a config
reads `null`, an experiment reads not-in-experiment.

## On-the-spot overrides

Layer a quick override on top of whatever `configureForTesting` /
`configureForOffline` set up. These are top-level functions — no object to pass:

```kotlin
import ai.shipeasy.overrideFlag
import ai.shipeasy.overrideConfig
import ai.shipeasy.overrideExperiment
import ai.shipeasy.clearOverrides

overrideFlag("new_checkout", true)
overrideConfig("billing_copy", "Pay now")
overrideExperiment("checkout_button", group = "treatment", params = mapOf("color" to "green"))

// drop every on-the-spot override between cases
clearOverrides()
```

An override always wins. Under `configureForTesting` there is no blob beneath, so
`clearOverrides()` reverts a seeded value too; under `configureForOffline` it
reverts to the snapshot.

## `configureForOffline(...)` — real rules, no network

Evaluate the **real** rules (rules + rollout, not just overrides) from a captured
blob, fully offline. Source it from a JSON `path` or an in-memory `snapshot`;
optional `flags` / `configs` / `experiments` overrides layer on top.

```kotlin
import ai.shipeasy.configureForOffline
import ai.shipeasy.Client

// From a JSON file on disk:
configureForOffline(path = "/path/to/snapshot.json")

val flags = Client(mapOf("user_id" to "u_123"))
flags.getFlag("new_checkout")
```

The `path` file is a JSON document shaped `{ "flags": …, "experiments": … }`. A
complete, valid minimal snapshot:

```json
{
  "flags": {
    "gates": {
      "new_checkout": { "enabled": true, "rolloutPct": 10000, "salt": "s" }
    },
    "configs": {
      "billing_copy": "Pay now"
    },
    "killswitches": {}
  },
  "experiments": {
    "experiments": {},
    "universes": {}
  }
}
```

A gate looks like `{ "enabled": true, "rolloutPct": 10000, "salt": "s" }` —
`rolloutPct` is in basis points, so `10000` = 100%, `5000` = 50%.

You can also pass an in-memory `snapshot` map (`mapOf("flags" to …,
"experiments" to …)`) instead of a `path`.

## Testing code that uses `Client(user)`

Your production code constructs `Client(user)` directly. In tests, just call a
`configureFor*` sibling at setup — it installs the test/offline state as the
global config — then construct `Client(user)` exactly as production does. No test
double or seam is needed.
