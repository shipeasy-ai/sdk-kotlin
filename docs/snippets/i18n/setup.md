Server-side: render the i18n loader tag into your document `<head>` with the
**public client key** and the profile. (The Kotlin SDK has no server `t()` —
rendering happens in the browser via the client SDK.) Assumes `configure()` ran
at startup — see [Installation](../../pages/installation.md).

```kotlin
import ai.shipeasy.devtoolsScriptTag
import ai.shipeasy.i18nScriptTag

// i18nScriptTag(clientKey = null, profile = null, baseUrl = null):
//   clientKey — the PUBLIC client key, never the server key
//               (default: the clientKey passed to configure())
//   profile   — the locale profile to hydrate, e.g. "{{PROFILE}}"
//               (default: the profile passed to configure(), else "en:prod")
//   baseUrl   — CDN origin override (default: configure()'s cdnBaseUrl)
// top-level function, backed by the global configure() state — no object to hold
val head = i18nScriptTag()

// devtoolsScriptTag(projectId = null, clientKey = null, baseUrl = null, defer = true)
// Hosted overlay — opens with Shift+Alt+S or ?se=1, and only for a signed-in
// Shipeasy session, so gating it on staff/env is optional.
val devtools = devtoolsScriptTag()
```
