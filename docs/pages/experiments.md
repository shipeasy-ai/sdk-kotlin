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

Record a conversion / success event so the experiment can compute lift. Use the
bound **`Client`** you already hold from `getExperiment` — the unit is derived
from the bound attribute bag (`user_id`, else `anonymous_id`), so there is no
user argument:

```kotlin
val flags = Client(currentUser)

val r = flags.getExperiment("checkout_button", mapOf("color" to "blue"))
// …present the treatment…
flags.track("{{SUCCESS_EVENT}}", mapOf("amount" to 49))
```

- `event` — the metric event, e.g. `{{SUCCESS_EVENT}}`.
- `props` — optional event props (private attributes are stripped, see
  [Advanced](advanced.md)).

`track` is fire-and-forget and never throws into the request path. It is a no-op
when the bound bag carries no unit, and on an offline / test engine.

### Low-level `Engine.track` (per-call user)

When you don't have a bound `Client` (e.g. a background job), `track` is also on
the **`Engine`**, which needs the user id explicitly:

```kotlin
engine.track("u_123", "{{SUCCESS_EVENT}}", mapOf("amount" to 49))
```

## Manual exposure — `logExposure(...)`

The server is stateless and never auto-logs exposures. Call `logExposure` on the
bound **`Client`** at the point you actually present the treatment so the
experiment counts the exposure (no user argument — the unit comes from the bound
bag):

```kotlin
flags.logExposure("checkout_button")
```

It re-evaluates the experiment for the bound user and POSTs a single `exposure`
event only if the user is enrolled (no-op otherwise, and in test mode).

### Low-level `Engine.logExposure` (per-call user)

```kotlin
engine.logExposure("u_123", "checkout_button")
```
