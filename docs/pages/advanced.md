# Advanced

## Private attributes

Attribute names usable for targeting but never persisted in analytics
(LD/Statsig `privateAttributes`). Since the server evaluates locally, private
attrs never leave for evaluation at all; the only egress is `/collect`, and the
listed keys are stripped from every outbound `track()` payload and `see()`
`extras`.

```kotlin
configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    privateAttributes = listOf("email", "ip"),
)
```

## Sticky bucketing

Pass a `StickyBucketStore` so `universe(name).assign()` locks a unit to its
first-assigned variant — changing allocation % or weights won't re-bucket
enrolled units (changing the experiment salt is the reshuffle lever). Absent ⇒
deterministic (fully backward compatible). A built-in in-memory store is
provided:

```kotlin
import ai.shipeasy.InMemoryStickyStore

configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    stickyStore = InMemoryStickyStore(),
)
```

Implement the `StickyBucketStore` interface to back it with Redis/DB for
multi-process servers.

## Anonymous-id bucketing (`AnonIdFilter`)

For logged-out traffic you need a *stable* unit so a fractional rollout buckets
the same on the server and in the browser. `AnonIdFilter` is a servlet `Filter`
that mints the shared `__se_anon_id` first-party cookie for any request without
one; evaluations then **default to it** as `anonymous_id`.

```kotlin
// Spring Boot — a default FilterRegistrationBean maps to all paths
@Bean
fun shipeasyAnonId() = FilterRegistrationBean(AnonIdFilter())

// logged-out request → buckets on the __se_anon_id cookie automatically
val flags = Client(loggedOutUser)
flags.getFlag("new_checkout")     // bound Client with no explicit unit
```

`jakarta.servlet-api` is `compileOnly`. Non-servlet stacks (Ktor, http4k,
Javalin) can use the `AnonId` primitives directly. An explicit
`user_id`/`anonymous_id` always wins. The cookie is non-`HttpOnly` by design so
the browser SDK buckets identically; a request with **no** unit still resolves a
fully-rolled (100%) gate as on. Cookie name + format are a cross-SDK contract —
see `18-identity-bucketing.md`.

### Anonymous id in a mobile app (`AnonStore`)

`AnonIdFilter` is for a **server** request. A shipped Android/JVM client app has
no request cookie — `ShipeasyClient` (see [Installation](installation.md#native-mobile-client--android-shipeasyclient))
instead **persists** the device's `__se_anon_id` so bucketing is stable across
app launches. Without persistence a fresh UUID every cold start silently
re-buckets every fractional rollout and experiment.

The core `shipeasy-kotlin` jar is pure-JVM and Android-free, so persistence is a
pluggable `AnonStore` (`get`/`set`, synchronous, best-effort). On Android, the
`ai.shipeasy:shipeasy-kotlin-android` artifact supplies
`SharedPreferencesAnonStore` and a one-call `configureAndroid(context, clientKey)`;
elsewhere pass your own:

```kotlin
import ai.shipeasy.AnonStore
import ai.shipeasy.configureClient

val store = object : AnonStore {
    override fun get(key: String): String? = /* read from your storage */ null
    override fun set(key: String, value: String) { /* persist */ }
}
configureClient(clientKey = "pk_live_…", store = store)
```

An `InMemoryAnonStore` ships for tests. The stable id is readable as
`shipeasyClient()?.anonymousId`.

## Exposure logging

`universe(name).assign()` is side-effect free. The exposure fires on the **first
`get()` read** of a param when the unit is enrolled — reading *is* the exposure,
so you don't call anything separately. The unit is derived from the bound
`Client` (its `user_id`, else `anonymous_id`):

```kotlin
val flags = Client(currentUser)
val cta = flags.universe("hero_cta").assign() // no exposure yet
cta.get("primary_label", "Sign up")           // enrolled → one deduped exposure fires here
```

Repeated `get()` reads for the same `(unit, experiment, group)` collapse to a
single exposure: deduped per process and durably per `(unit, experiment, group)`
server-side. To read a param **without** logging an exposure — e.g. inspecting a
value you aren't presenting — use `peek(field, fallback)`:

```kotlin
cta.peek("primary_label", "Sign up") // read-only — never logs an exposure
```

See [Experiments](experiments.md).

## Change listeners — `onChange`

Subscribe to data-change notifications with the top-level `onChange` function.
The listener fires after a background poll brings **new** data (HTTP 200, not
304) — not for the initial fetch, and never under `configureForTesting` /
`configureForOffline`. Requires `configure(..., poll = true)`. Returns an
unsubscribe function.

```kotlin
import ai.shipeasy.onChange

val unsubscribe = onChange {
    // rebuild any cached evaluations, warm a downstream cache, etc.
}
// later
unsubscribe()
```

Each listener runs in a try/catch; a throwing listener is logged and does not
affect the others.

## Server-side rendering (SSR)

Emit the request's evaluated flags as a declarative `<script>` tag so the
browser SDK has them on first paint. `bootstrapScriptTag` (a top-level function
backed by the global `configure()` state) carries the payload in `data-*`
attributes (**no key**); the static loader hydrates `window.__SE_BOOTSTRAP` and
writes the `__se_anon_id` cookie so the browser buckets identically to the
server.

```kotlin
import ai.shipeasy.bootstrapScriptTag
import ai.shipeasy.i18nScriptTag

val user = mapOf("user_id" to "u_123")

// Two tags for the document <head>. The PUBLIC client key (not the server key)
// goes on the i18n loader tag — and it comes from configure(), so the callsite
// does not repeat it.
val head = bootstrapScriptTag(user, anonId = anonId) + i18nScriptTag()
```

### Every argument is optional

All three tag functions fall back to what `configure()` set, so the bare call is
the normal one — pass an argument only to override that one tag:

| Function | Signature | Defaults from `configure` |
| --- | --- | --- |
| `i18nScriptTag` | `(clientKey?, profile?, baseUrl?)` | `clientKey`, `profile`, `cdnBaseUrl` |
| `bootstrapScriptTag` | `(user = emptyMap(), anonId?, i18nProfile?, baseUrl?)` | anonymous request, `profile`, `cdnBaseUrl` |
| `devtoolsScriptTag` | `(projectId?, clientKey?, baseUrl?, defer = true)` | `projectId`, `clientKey`, `cdnBaseUrl` |

```kotlin
configure(
    apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
    clientKey = System.getenv("SHIPEASY_CLIENT_KEY"),  // PUBLIC key, for the tags
    projectId = System.getenv("SHIPEASY_PROJECT_ID"),  // for the devtools tag
    profile = "en:prod",
)
```

A tag still renders when a value is missing (the browser bundle reports what it
needs), but the SDK logs a warning naming the `configure` argument to fill in —
once per argument, not once per render.

### Devtools overlay tag

`devtoolsScriptTag()` emits the hosted devtools overlay bundle — nothing to
install, no overlay code in your artifact. It reads the project id and public
client key off the tag and opens with **Shift+Alt+S** or on any page loaded with
`?se=1`. It is `defer`red unless you pass `defer = false`: a developer tool never
belongs on the critical rendering path.

```kotlin
import ai.shipeasy.devtoolsScriptTag

val head = devtoolsScriptTag()
```

Adding it unconditionally is fine: the overlay only opens for someone with a
signed-in Shipeasy session, so on a page where nobody has authenticated it
renders nothing and says nothing. Gating it on your own staff or environment
check is **optional** — worth it only if you'd rather the bundle not load for
end users at all:

```kotlin
val head = if (user.isStaff) devtoolsScriptTag() else ""
```

### Identity coherence (no anon→identified flip)

When you pass an **identified** `user`, the tag also carries that identity as a
`data-user` attribute (the user's traits, minus `anonymous_id`, as escaped JSON).
The browser SDK adopts it on first paint, so a Kotlin-backend + JS-frontend app
renders as the identified user immediately — no anonymous-then-identified flip.
An anonymous request (only `anonymous_id`, or an empty user) emits **no**
`data-user`. See `experiment-platform/18-identity-bucketing.md`.
