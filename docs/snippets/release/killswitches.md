Check a kill switch — `true` means the feature is killed. Assumes `configure()`
ran at startup — see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user)
val flags = Client(currentUser)

// getKillswitch(name, switchKey = null): without switchKey → true when the whole
// kill switch is killed; with switchKey → true when that per-key override is on.
if (flags.getKillswitch("{{RESOURCE_NAME}}", switchKey = null)) {
    return serviceUnavailable()   // killed — short-circuit
}
```
