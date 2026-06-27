Configure once, then read a feature gate with a bound `Client`.

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))

val flags = Client(currentUser)
if (flags.getFlag("{{RESOURCE_NAME}}")) {
    // gate is on for this user
}
```
