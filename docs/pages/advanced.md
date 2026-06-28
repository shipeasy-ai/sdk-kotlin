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

Pass a `StickyBucketStore` so `getExperiment` locks a unit to its
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

## Manual exposure

The server is stateless and never auto-logs. Call `logExposure` where you
present the treatment. The bound `Client` derives the unit from the bound user:

```kotlin
val flags = Client(currentUser)
flags.logExposure("checkout_button")
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
