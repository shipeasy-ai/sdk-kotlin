# A/B experiments (`universe().assign()` + `track`)

Experiments are read by **universe**. A universe is a mutual-exclusion pool: a
unit lands in **at most one** experiment in it. `assign()` picks that experiment
(if any) and returns the assigned group plus its resolved parameters — it is
side-effect free. The exposure fires on the **first `get()` read** of a param;
use `peek()` to read without logging one. You read parameters with
`assign().get(field, fallback)` and record a conversion with `track`.

## Read an experiment

```kotlin
import ai.shipeasy.Client

val flags = Client(currentUser) // construct once per user (cheap)

// Ask the UNIVERSE, not the experiment: the unit lands in ≤1 experiment in it.
val cta = flags.universe("hero_cta").assign()

// Read a param: variant override ?? universe default ?? your fallback.
render(cta.get("primary_label", "Sign up"))
```

On the **server** the user is bound at construction, so `assign()` takes no
argument.

## `Assignment`

```kotlin
class Assignment {
    val name: String?      // the experiment the unit landed in, or null when not enrolled
    val group: String?     // the assigned variant, or null when not enrolled
    val enrolled: Boolean  // == (group != null)
    fun get(field: String, fallback: Any? = null): Any?  // variant ?? universe default ?? fallback — logs the exposure on first read
    fun peek(field: String, fallback: Any? = null): Any? // same resolution, but read-only — never logs an exposure
}
```

The first `get(...)` read logs a single (deduped) exposure when the unit is
enrolled; `peek(...)` resolves the same value without ever logging one — reach
for it when you're inspecting a param but not actually presenting the treatment.

When the unit isn't enrolled (targeting / holdout / allocation), `enrolled` is
`false`, `group` and `name` are `null`, and `get(field, fallback)` returns the
universe default if there is one, else your `fallback` — so reading a param is
always safe.

```kotlin
val cta = flags.universe("hero_cta").assign()
if (cta.enrolled) {
    // cta.group is the variant, e.g. "treatment"
}
val label = cta.get("primary_label", "Sign up") // never throws
```

`get` returns `Any?`; cast to the type you stored (e.g.
`cta.get("primary_label") as? String`).

## Track conversions

Record the success event so the analysis pipeline can compute lift. Conversion
events are attributed to the bound user. You already have a `Client` — call
`track` on the **same handle**, so an experiment is end-to-end Client-only (the
unit is derived from the bound attributes: `user_id`, else `anonymous_id`):

```kotlin
// Same bound Client you assigned with — no user arg.
flags.track("{{SUCCESS_EVENT}}", mapOf("amount" to 49))
```

- `event` — the metric event, e.g. `{{SUCCESS_EVENT}}`.
- `props` — optional event props (private attributes are stripped, see
  [Advanced](advanced.md)).

`track` is fire-and-forget and never throws into the request path. It is a no-op
when the bound bag carries no unit, and under `configureForTesting` /
`configureForOffline`.

## Iterating over many users

When you don't have a single bound user — e.g. a batch job scoring many users —
construct a fresh `Client` per user inside the loop. It's cheap (it delegates to
the configuration built once at startup; it opens no connection):

```kotlin
for (user in users) {
    val flags = Client(user) // construct once per user (cheap)
    val cta = flags.universe("hero_cta").assign()
    flags.track("{{SUCCESS_EVENT}}", mapOf("group" to (cta.group ?: "none")))
}
```

## Exposure logging

`assign()` is side-effect free; the exposure fires on the **first `get()` read**
of a param when the unit is enrolled. It's deduped per process and durably per
`(unit, experiment, group)` server-side, so re-reads never double-count. Use
`peek(field, fallback)` to read a param without logging an exposure. See
[Advanced](advanced.md).
