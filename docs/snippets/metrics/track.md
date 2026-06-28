Track a metric/conversion event from the bound `Client`. Metrics in the
dashboard are computed from these events. Assumes `configure()` ran at startup —
see [Installation](../../pages/installation.md).

### Track an event

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user)
val flags = Client(currentUser)

// track(event, props = emptyMap())
//   event — the event your metric is built on (required)
//   props — optional payload; numeric/string fields you can sum/filter on in a
//           metric (private attributes are stripped before egress)
flags.track("{{EVENT_NAME}}", mapOf("amount" to 49, "currency" to "usd"))
```

Fire-and-forget (never blocks your response) and a no-op under
`configureForTesting` / `configureForOffline`. The unit is the bound user
(`user_id`, else `anonymous_id`); with no unit the call is a no-op.

### Track without properties

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user)
val flags = Client(currentUser)

flags.track("{{EVENT_NAME}}")   // props are optional (default emptyMap())
```
