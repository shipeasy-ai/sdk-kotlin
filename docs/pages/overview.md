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

`configure()` builds and stores ONE process-global **`Engine`**; the lightweight
`Client(user)` reads that engine and delegates. The user (and the `attributes`
transform you register at configure time) is bound when you construct the
`Client`, so its methods take no user argument.

## Engine vs Client

| Type | What it is | When you use it |
| ---- | ---------- | --------------- |
| **`Engine`** | Heavyweight: owns the API key, HTTP client, cached blobs, the poll timer, local overrides, telemetry and `see()`. One per process. | Built for you by `configure()`. Construct directly only for multiple keys, tests, the per-call `user` form, or offline snapshots. |
| **`Client(user)`** | Lightweight user-bound handle. No own connection. | Per request / per user — the everyday evaluation surface. |

> **Renamed in 0.8.0 (BREAKING):** the heavyweight class formerly called `Client`
> is now `Engine`. `Client` is now the bound handle. Replace
> `Client(apiKey = …)` with `Engine(apiKey = …)` or switch to `configure(...)`.

## Feature reference

- [Installation](installation.md) — Gradle dependency, runtime, imports.
- [Configuration](configuration.md) — `configure()` in full, the `attributes` transform, init/poll.
- [Flags](flags.md) — `getFlag` / `getFlagDetail`.
- [Configs](configs.md) — `getConfig`.
- [Kill switches](killswitches.md) — `getKillswitch`.
- [Experiments](experiments.md) — `getExperiment`, `ExperimentResult`, `track`.
- [i18n](i18n.md) — SSR bootstrap + the client-side translation story.
- [Error reporting](error-reporting.md) — `see()` structured error reporting.
- [Testing](testing.md) — `Engine.forTesting()` + `override*`, offline snapshots.
- [OpenFeature](openfeature.md) — provider availability.
- [Advanced](advanced.md) — private attributes, sticky bucketing, anon-id, manual exposure, SSR.
