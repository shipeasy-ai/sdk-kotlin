Check a kill switch — `true` means the feature is killed.

```kotlin
import ai.shipeasy.Client

val flags = Client(currentUser)
if (flags.getKillswitch("{{RESOURCE_NAME}}")) {
    return serviceUnavailable()   // killed — short-circuit
}
```
