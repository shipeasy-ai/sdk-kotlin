Read a dynamic config value, with a default when the key is absent.

```kotlin
import ai.shipeasy.Client

val flags = Client(currentUser)
val value = flags.getConfig("{{RESOURCE_NAME}}", default = "Pay now")
```
