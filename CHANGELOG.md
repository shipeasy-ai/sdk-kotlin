# Changelog

## Unreleased

- **Flag/config default values.** `getFlag(name, user, default = false)` and
  `getConfig(name, default = null)` gained an optional default. The flag default
  is returned only when the gate cannot be evaluated (client not ready or flag
  not found) — never for a flag that legitimately evaluates to `false`; the
  config default is returned when the key is absent. The existing 2-arg call
  sites stay valid (additive, backward-compatible).
- **Evaluation detail (`getFlagDetail`).** New `FlagDetail(value, reason)` and
  `getFlagDetail(name, user)` report *why* a gate resolved (LaunchDarkly
  `variationDetail` parity) via the `Reason` constants `OVERRIDE`,
  `CLIENT_NOT_READY`, `FLAG_NOT_FOUND`, `OFF`, `RULE_MATCH`, `DEFAULT`. The
  reason is computed at the SDK boundary; the canonical eval is untouched.
  `getFlag` now delegates to `getFlagDetail`.
- **Change listeners (`onChange`).** Subscribe to data-change notifications;
  the listener fires after a background poll brings new data (200, not 304) and
  returns an unsubscribe function. Never fires for the initial `init()` fetch or
  in an offline/local-mode client. Listeners are isolated (try/catch + log).
- **Offline snapshot data source.** `Client.fromSnapshot(flags, experiments)`
  and `Client.fromFile(path)` build a fully offline client (no network;
  `init()`/`initOnce()`/`track()` no-op, telemetry off) seeded with the real
  blobs so evaluations run the canonical eval against the snapshot. Local
  overrides still apply on top.

- **Local-override test utility.** Added `Client.forTesting()` — a no-network,
  no-key client (telemetry off; `init()`/`initOnce()`/`track()` are no-ops) for
  unit tests. New override setters `overrideFlag`, `overrideConfig`,
  `overrideExperiment`, and `clearOverrides` (also usable on a normal client) let
  you seed exact values; an override always wins over fetched state. See the
  "Testing" section of the README.

## 0.3.0

- **Anonymous bucketing (`__se_anon_id`).** Added `AnonIdFilter`, a servlet
  `Filter` that mints the shared `__se_anon_id` first-party cookie for any
  request without one, and `AnonId` framework-agnostic primitives. Gate/
  experiment evaluations now default to the cookie id as `anonymous_id` (via a
  request `ThreadLocal`), so anonymous visitors bucket consistently across
  server renders and the browser with no per-call wiring. `jakarta.servlet-api`
  is a `compileOnly` dependency (your container supplies it at runtime).
  Implements the cross-SDK contract in `18-identity-bucketing.md`.
- **Eval fix (no-unit gate rule).** A request with no `user_id`/`anonymous_id`
  now resolves a fully-rolled (100%) gate as **on** instead of always off; a
  fractional gate is still off until a stable unit exists. Matches the
  TypeScript reference SDK. Targeting rules are still evaluated first.

## 0.2.0

- Per-evaluation usage telemetry (fire-and-forget, on by default).

## 0.1.0

- Initial release: feature flags, configs, experiments, metric tracking.
