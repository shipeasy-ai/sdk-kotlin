# Flags (feature gates)

`getFlag(name)` returns a `Boolean` — whether the gate is **on** for the
evaluated user.

## Bound `Client` form

```kotlin
val flags = Client(currentUser)

if (flags.getFlag("new_checkout")) {
    // gate is on for this user
}
```

## Default / fallback

`getFlag` accepts an optional `default` (defaults to `false`). The default is
returned **only** when the gate cannot be evaluated — the SDK isn't initialized
yet, or the flag isn't present in the loaded blob. It is **never** returned for a
flag that legitimately evaluates to `false`.

```kotlin
// returns `true` only if the SDK isn't ready / the flag is unknown;
// a known flag that evaluates false still returns false
flags.getFlag("new_checkout", default = true)
```

## `getFlagDetail` — value + reason

`getFlagDetail` returns the value plus a `reason` explaining it (LaunchDarkly
`variationDetail` parity). The reason is computed at the SDK boundary; the
canonical evaluation is untouched.

```kotlin
val d: FlagDetail = flags.getFlagDetail("new_checkout")
d.value    // Boolean
d.reason   // one of the Reason constants
```

`reason` is one of the `ai.shipeasy.Reason` constants:

| `Reason` constant  | Meaning                                              |
| ------------------ | ---------------------------------------------------- |
| `OVERRIDE`         | A local override supplied the value (no telemetry).  |
| `CLIENT_NOT_READY` | SDK not initialized — no rules blob loaded yet.      |
| `FLAG_NOT_FOUND`   | The gate name isn't present in the loaded blob.      |
| `OFF`              | Gate present but disabled / killed.                  |
| `RULE_MATCH`       | The gate evaluated `true` (rules + rollout passed).  |
| `DEFAULT`          | The gate evaluated `false`.                          |

`getFlag` is implemented on top of `getFlagDetail` and returns `.value`.

## Boolean semantics

`getFlag` is strictly boolean. For typed values use [configs](configs.md); for
variant assignment use [experiments](experiments.md).
