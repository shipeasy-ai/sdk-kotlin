Server-side: render the i18n loader tag into your document `<head>` with the
**public client key** and the profile. (The Kotlin SDK has no server `t()` —
rendering happens in the browser via the client SDK.) Assumes `configure()` ran
at startup — see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.currentEngine

// grab the process-global engine built by configure(); construct/resolve once
// per callsite (the script tag is rendered per response)
val engine = currentEngine() ?: error("configure() must run before SSR")

// i18nScriptTag(clientKey, profile = "en:prod", baseUrl = null):
//   clientKey — the PUBLIC client key (never the server key)
//   profile   — the locale profile to hydrate, e.g. "{{PROFILE}}"
//   baseUrl   — CDN origin override; default https://cdn.shipeasy.ai
val head = engine.i18nScriptTag(clientKey, "{{PROFILE}}")
```
