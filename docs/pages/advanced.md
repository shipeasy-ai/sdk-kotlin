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
// goes on the i18n loader tag.
val head = bootstrapScriptTag(user, anonId = anonId) +
           i18nScriptTag(clientKey, "en:prod")
```

`bootstrapScriptTag` also accepts `i18nProfile` and `baseUrl` (default
`https://cdn.shipeasy.ai`).
