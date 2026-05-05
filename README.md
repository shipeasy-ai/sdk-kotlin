# shipeasy (Kotlin)

Server SDK for [Shipeasy](https://shipeasy.dev). JVM/Android-compatible.

```kotlin
implementation("ai.shipeasy:shipeasy:0.1.0")
```

```kotlin
import ai.shipeasy.Client

val c = Client(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))
runBlocking { c.init() }

c.getFlag("new_checkout", mapOf("user_id" to "u_123"))
c.getConfig("billing_copy")
val r = c.getExperiment("checkout_button", mapOf("user_id" to "u_123"), mapOf("color" to "blue"))
c.track("u_123", "purchase", mapOf("amount" to 49))
c.close()
```
