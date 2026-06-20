# Changelog

## Unreleased

- **`see()` structured error reporting.** New error grammar mirroring
  `@shipeasy/sdk`. Instance methods `client.see(problem)`,
  `client.seeViolation(name)`, `client.controlFlowException(err)`, plus
  package-level `see(...)`, `seeViolation(...)`, `controlFlowException(...)`
  backed by a default client (the last-constructed `Client` registers itself;
  override with `setDefaultClient`). A global call before any client logs a
  warning and is a no-op. The fluent chain `see(e).causesThe(subject)
  .extras(map).to(outcome)` builds a `{type:"error", kind, error_type, message,
  stack?, subject, outcome, extras?, side:"server", env?, sdk_version, ts}`
  event and fire-and-forgets it to `/collect`, exactly like `track()` — `to()`
  is the terminal (idempotent), `causesThe()`/`extras()` are order-independent
  setters before it. `controlFlowException(e).because(reason)` marks the
  throwable expected and reports nothing. Extras are sanitized (≤20 keys,
  200-char string values, null/non-primitive dropped) and private attributes
  are stripped; a per-process spam limiter (30s dedup, 25 cap) bounds chatter.
  No-op in local/offline mode. New `VERSION` constant is the single source of
  the event's `sdk_version`.
- **Private attributes.** New `privateAttributes` client option (a list of
  attribute names). These attributes remain usable for targeting (the server
  evaluates locally, so they never leave for evaluation), but the listed keys are
  stripped from every outbound `track()` properties payload before it is POSTed
  to `/collect`. Matches the LD/Statsig `privateAttributes` contract and the TS
  reference SDK.
- **Manual exposure (`logExposure`).** New `logExposure(userId, experimentName)`.
  The server never auto-logs exposures; call this at the point you actually
  present the treatment. It re-evaluates the experiment and, if the user is
  enrolled, POSTs a single `{type:"exposure", experiment, group, user_id, ts}`
  event to `/collect`. No-op when the user isn't enrolled or in local/offline
  mode.
- **Sticky bucketing.** New `StickyBucketStore` interface (`get(unit)` →
  `Map<String, StickyEntry>?`, `set(unit, exp, entry)`) with `StickyEntry(group,
  salt8)`, plus a built-in thread-safe `InMemoryStickyStore`. Supply a store via
  the `stickyStore` client option (or `Client.fromSnapshot(flags, experiments,
  stickyStore)`). When present, experiment evaluation locks a unit to its
  first-assigned variant: after the holdout and before allocation, a stored entry
  whose `salt8` still matches `experiment.salt.take(8)` skips the allocation gate
  and returns the stored group with no re-pick (so a shrinking allocation or
  reweight keeps enrolled units in place). A fresh pick is persisted; a salt
  mismatch or vanished group re-buckets and overwrites. Absent ⇒ deterministic
  (fully backward compatible). Mirrors the canonical TS impl (doc 20 §2).
- **Experiment `bucketBy`.** Experiment evaluation now honors a per-experiment
  `bucketBy` attribute (e.g. `company_id`) so a whole org can be kept on one
  variant. When set and the user carries a non-empty string (or a number) at
  that attribute, the holdout, allocation, and group hashes all bucket on it;
  otherwise it falls back to `user_id` ?? `anonymous_id` (matching gates). No
  resolvable unit ⇒ not enrolled. Matches the canonical TS/core impl.
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
