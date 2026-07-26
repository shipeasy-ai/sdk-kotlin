# Overview

`shipeasy-kotlin` (`ai.shipeasy:shipeasy-kotlin`) is the **server-side** Shipeasy
SDK for the JVM (and Android-compatible). It evaluates feature flags (gates),
dynamic configs, kill switches and A/B experiments **locally** against rule blobs
it fetches from the Shipeasy edge — no per-evaluation network call on the hot
path.

## Mental model: `configure()` once, then `Client(user)`

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client

// Once, at app boot.
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"))

// Per request — cheap, no own connection/poll.
val flags = Client(currentUser)
flags.getFlag("new_checkout")        // → Boolean (no user arg; user bound at construction)
```

You learn exactly two things:

1. **`configure()`** (and its test/offline siblings `configureForTesting()` /
   `configureForOffline()`) — call it once at app boot.
2. **`Client(user)`** — the cheap, user-bound handle for every read:
   `getFlag` / `getFlagDetail` / `getConfig` / `getKillswitch` /
   `universe(name).assign()` / `track`.

The user (and the `attributes` transform you register at configure time) is bound
when you construct the `Client`, so its methods take no user argument. Construct a
`Client` per request/user — it is cheap and opens no connection, fetch, or poll of
its own.

A handful of top-level package functions cover everything else without naming a
heavyweight object: `overrideFlag` / `overrideConfig` / `overrideExperiment` /
`clearOverrides`, `onChange`, `bootstrapScriptTag` / `i18nScriptTag` /
`devtoolsScriptTag`, and the
`see()` family.

## Shipping in an Android app? Use `ShipeasyClient`

`configure()` / `Client(user)` above is the **server** SDK — it holds a server
key and evaluates rules locally. **Never embed a server key in a shipped app.**
For an Android app, use `configureAndroid(context, clientKey)` +
`ShipeasyClient`: a **public client key**, server-side evaluation over
`POST /sdk/evaluate`, and a **persisted device `anonymous_id`** so logged-out
users bucket identically across launches. It ships in the companion artifact
`ai.shipeasy:shipeasy-kotlin-android`; the core jar stays pure-JVM (Ktor / Spring
/ http4k servers are unaffected). See [Installation](installation.md#native-mobile-client--android-shipeasyclient).

## Feature reference

- [Installation](installation.md) — Gradle/Maven dependency, runtime, imports, and the canonical `configure()` reference.
- [Configuration](configuration.md) — `configure()` in full, the `attributes` transform, init/poll.
- [Flags](flags.md) — `getFlag` / `getFlagDetail`.
- [Configs](configs.md) — `getConfig`.
- [Kill switches](killswitches.md) — `getKillswitch`.
- [Experiments](experiments.md) — `universe(name).assign()`, `Assignment`, `track`.
- [i18n](i18n.md) — SSR bootstrap + the client-side translation story.
- [Error reporting](error-reporting.md) — `see()` structured error reporting.
- [Testing](testing.md) — `configureForTesting()` / `configureForOffline()` + the override helpers.
- [OpenFeature](openfeature.md) — provider availability.
- [Advanced](advanced.md) — private attributes, sticky bucketing, anon-id, manual exposure, SSR.
