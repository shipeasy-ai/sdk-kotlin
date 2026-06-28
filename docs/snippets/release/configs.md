Read a dynamic config value, with a default when the key is absent. Assumes
`configure()` ran at startup — see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.Client

// construct once per callsite (cheap; binds the user)
val flags = Client(currentUser)

// getConfig(name, default = null): default is returned when the key is absent.
val value = flags.getConfig("{{CONFIG_KEY}}", default = "Pay now")
```
