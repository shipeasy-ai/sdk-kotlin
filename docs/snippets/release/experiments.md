Assign a variant and track the conversion event.

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

val engine = configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))

val r = Client(currentUser).getExperiment("{{RESOURCE_NAME}}", mapOf("color" to "blue"))
if (r.inExperiment) {
    @Suppress("UNCHECKED_CAST")
    val color = (r.params as? Map<String, Any?>)?.get("color")
}

// on conversion
engine.track(userId, "{{SUCCESS_EVENT}}", mapOf("amount" to 49))
```
