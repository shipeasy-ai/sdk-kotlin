# Experiments (A/B tests)

`getExperiment(name, defaultParams)` assigns the user to a variant and returns
an `ExperimentResult`.

## `ExperimentResult`

```kotlin
data class ExperimentResult(
    val inExperiment: Boolean,   // is the user enrolled?
    val group: String,           // assigned group/variant name
    val params: Any?,            // variant parameters (your typed payload)
)
```

## Bound `Client` form

```kotlin
val flags = Client(currentUser)

val r = flags.getExperiment("checkout_button", mapOf("color" to "blue"))
if (r.inExperiment) {
    @Suppress("UNCHECKED_CAST")
    val params = r.params as? Map<String, Any?> ?: emptyMap()
    val color = params["color"]   // variant param
}
```

The second argument is `defaultParams`: returned as `params` when the experiment
yields no params (e.g. not enrolled / not loaded). Pass `null` for none.

## Low-level `Engine` form (per-call user)

```kotlin
val r = engine.getExperiment(
    "checkout_button",
    mapOf("user_id" to "u_123"),
    mapOf("color" to "blue"),   // defaultParams
)
```

## Tracking conversions — `track(...)`

Record a conversion / success event so the experiment can compute lift. `track`
lives on the **`Engine`** (it needs the user id and a live connection):

```kotlin
engine.track("u_123", "{{SUCCESS_EVENT}}", mapOf("amount" to 49))
```

- `userId` — the unit the experiment buckets on.
- `eventName` — the metric event, e.g. `{{SUCCESS_EVENT}}`.
- `properties` — optional event props (private attributes are stripped, see
  [Advanced](advanced.md)).

`track` is fire-and-forget and never throws into the request path. It is a no-op
on an offline / test engine.

## Manual exposure — `logExposure(...)`

The server is stateless and never auto-logs exposures. Call `logExposure` at the
point you actually present the treatment so the experiment counts the exposure:

```kotlin
engine.logExposure("u_123", "checkout_button")
```

It re-evaluates the experiment for the user and POSTs a single `exposure` event
only if the user is enrolled (no-op otherwise, and in test mode).
