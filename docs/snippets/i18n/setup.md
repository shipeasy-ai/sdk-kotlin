Server-side: render the i18n loader tag into your document `<head>` with the
**public client key** and the profile. (The Kotlin SDK has no server `t()` —
rendering happens in the browser via the client SDK.)

```kotlin
val engine = configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))

// clientKey is the PUBLIC client key (never the server key)
val head = engine.i18nScriptTag(clientKey, "{{PROFILE}}")
```
