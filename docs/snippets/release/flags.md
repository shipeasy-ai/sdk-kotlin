Read a feature gate with a bound `Client`. Assumes `configure()` ran at startup
— see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user, no own connection/poll)
val flags = Client(currentUser)

// getFlag(name, default = false): default is returned ONLY when the gate can't
// be evaluated (client not ready / flag unknown) — never for a real `false`.
if (flags.getFlag("{{RESOURCE_NAME}}", default = false)) {
    // gate is on for this user
}
```
