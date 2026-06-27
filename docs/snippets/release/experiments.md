Assign a variant and track the conversion event — both on the same bound
`Client`. Assumes `configure()` ran at startup — see
[Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user)
val client = Client(currentUser)

// getExperiment(name, defaultParams): defaultParams is returned as `params` when
// the user isn't in the experiment (or the experiment has no params).
val r = client.getExperiment("{{RESOURCE_NAME}}", mapOf("color" to "blue"))
if (r.inExperiment) {
    @Suppress("UNCHECKED_CAST")
    val color = (r.params as? Map<String, Any?>)?.get("color")
    // …present the treatment…
}

// on conversion — same bound Client, no user arg (unit comes from the bound bag)
// track(event, props = emptyMap()): event = the metric; props = optional event
// properties (private attributes are stripped).
client.track("{{SUCCESS_EVENT}}", mapOf("amount" to 49))
```
