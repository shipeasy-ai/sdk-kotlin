# CLAUDE.md — ai.shipeasy:shipeasy-kotlin

Guidance for AI agents (and humans) working in this repository.

## What this is

The **server** Kotlin/JVM SDK for [Shipeasy](https://shipeasy.ai): feature flags,
dynamic configs, kill switches, A/B experiments, metric tracking, `see()` error
reporting, and SSR/i18n helpers. Server-key only; never embed in a client app.
Source under `src/main/kotlin/ai/shipeasy/`; tests under `src/test/kotlin/`
(`kotlin.test` + JUnit Platform, `gradle test`). `Engine` is an `actor`-free
coroutine class (`init`/`initOnce` are `suspend`).

There is **no OpenFeature provider** in Kotlin — `getFlag` / `getFlagDetail` are
the interop surface.

## The documented public surface (this is a contract)

Users are taught exactly **two** things, and the docs must never drift from them:

1. **`configure()`** — and its siblings `configureForTesting()` /
   `configureForOffline()` — for setup.
2. **`Client(user)`** — the cheap, user-bound handle for *all* reads
   (`getFlag` / `getFlagDetail` / `getConfig` / `getKillswitch` / `track`, plus
   universe assignment via `universe(name).assign()`).

Plus the top-level package functions that let users avoid the heavyweight object:
`overrideFlag` / `overrideConfig` / `overrideExperiment` / `clearOverrides`,
`onChange`, `bootstrapScriptTag` / `i18nScriptTag` / `devtoolsScriptTag`, and the
`see()` family.

**The `Engine` class is an internal detail. Do NOT document it.** It stays public
for advanced/back-compat use, but no page, snippet, skill, or the README should
tell a user to construct or call an `Engine`. New user-facing capability should
get a `configure`-style or top-level affordance, then be documented through it.

## HARD RULE: change the SDK → update the docs in the SAME change

`docs/` is the published, user-facing source of truth (rendered at
<https://shipeasy-ai.github.io/sdk-kotlin/> and ingested by the Shipeasy CLI/MCP
`docs` tooling and the central docs portal). Any change to the SDK's **public API
or behaviour** updates the relevant `docs/pages/*.md`, the matching
`docs/snippets/**`, and `docs/skill/SKILL.md` in the same commit; new
page/snippet/placeholder → also `docs/manifest.json`. See
[`docs/CLAUDE.md`](docs/CLAUDE.md).

**`README.md` is generated — do not hand-edit it.** It is assembled from the docs
by the JDK-only single-file program `tools/GenReadme.java` (which also re-syncs the
embedded `src/main/resources/shipeasy-skill/SKILL.md`). After editing `docs/`, run:

```bash
java tools/GenReadme.java
```

CI (`.github/workflows/tests.yml`) re-runs it and fails if `README.md` or the
embedded skill drifts.

## Versioning & release

- Bump **both** `version` in `build.gradle.kts` and `VERSION` in
  `src/main/kotlin/ai/shipeasy/See.kt` (sent on every `see()` event), and add a
  `CHANGELOG.md` entry.
- Bump the coordinates the docs hand out too — the `implementation`/`<version>`
  lines in `README.md`, `docs/pages/installation.md` and `docs/skill/SKILL.md`,
  for both `shipeasy-kotlin` and `shipeasy-kotlin-android`. They were pinned at
  `0.10.0`/`0.14.0` while Central served `0.21.1`.
- Publishing is **push-to-`main`** (Maven Central via the vanniktech publish
  plugin; the workflow **gracefully skips** without the signing/credential
  secrets). The build pins Kotlin 1.9.23 + Gradle 8.7 with `jvmToolchain(11)`.

## Checks before you commit

- `gradle test` (the suite is hermetic — no network). The build needs a JDK 11
  toolchain; CI provides it.
- New public behaviour ships with a test.
- Docs updated per the hard rule; `docs/manifest.json` stays valid JSON and every
  path it lists exists.
- `java tools/GenReadme.java` and commit the result (CI checks it's in sync).
