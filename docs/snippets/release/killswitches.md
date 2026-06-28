Check a kill switch — `true` means the feature is killed. Assumes `configure()`
ran at startup — see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user)
val flags = Client(currentUser)

// getKillswitch(name, switchKey = null): without switchKey → true when the whole
// kill switch is killed; with switchKey → true when that named per-key override
// is on (an unconfigured key falls back to the top-level value).
if (flags.getKillswitch("{{KILLSWITCH_KEY}}", switchKey = null)) {
    return serviceUnavailable()   // killed — short-circuit
}
```
