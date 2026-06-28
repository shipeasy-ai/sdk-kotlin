Server-side: render the i18n loader tag into your document `<head>` with the
**public client key** and the profile. (The Kotlin SDK has no server `t()` —
rendering happens in the browser via the client SDK.) Assumes `configure()` ran
at startup — see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.i18nScriptTag

// i18nScriptTag(clientKey, profile = "en:prod", baseUrl = null):
//   clientKey — the PUBLIC client key (never the server key)
//   profile   — the locale profile to hydrate, e.g. "{{PROFILE}}"
//   baseUrl   — CDN origin override; default https://cdn.shipeasy.ai
// top-level function, backed by the global configure() state — no object to hold
val head = i18nScriptTag(clientKey, "{{PROFILE}}")
```
