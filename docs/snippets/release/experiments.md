Assign a unit within a universe (a mutual-exclusion pool — the unit lands in ≤1
experiment), read the assigned params, then record the conversion event on the
same bound `Client`. Assumes `configure()` ran at startup — see
[Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user + runs the attributes transform)
val flags = Client(currentUser)

// universe(name).assign() → Assignment
//   name   — the UNIVERSE name (not an experiment); the unit lands in ≤1 experiment
//   .name     — the experiment the unit landed in, or null when not enrolled
//   .group    — the assigned variant, or null when not enrolled
//   .enrolled — == (group != null)
//   .get(field, fallback) — variant override ?? universe default ?? fallback
// Server: assign() takes no arg (user bound at construction).
val exp = flags.universe("{{EXPERIMENT_KEY}}").assign()

render(exp.get("primary_label", "Sign up")) // always safe — falls back when not enrolled

// on conversion — same bound Client, no user arg (unit comes from the bound bag)
// track(event, props = emptyMap()): event = the metric; props = optional event
// properties (private attributes are stripped).
flags.track("{{SUCCESS_EVENT}}", mapOf("group" to (exp.group ?: "none")))
```
