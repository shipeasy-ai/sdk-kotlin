# Kill switches

`getKillswitch(name)` returns a `Boolean`: `true` when the kill switch is
**killed** (the feature should be turned OFF). Kill switches ride in the same
flags blob as gates and configs.

## Bound `Client` form

```kotlin
val flags = Client(currentUser)

if (flags.getKillswitch("payments")) {
    // payments are killed — short-circuit
    return serviceUnavailable()
}
```

## Per-switch override key

A kill switch can carry named per-key override **switches**. Pass `switchKey` to
read one specific switch: `true` when that named override is on.

```kotlin
flags.getKillswitch("payments", switchKey = "eu_region")   // → Boolean
```

## Low-level `Engine` form

`getKillswitch` is **not user-scoped** (it forwards directly):

```kotlin
engine.getKillswitch("payments")
engine.getKillswitch("payments", switchKey = "eu_region")
```

## Semantics

- Without `switchKey`: returns `true` when the whole kill switch is killed.
- With `switchKey`: returns `true` when that specific per-key override switch is on.
- Unknown kill switches / switches return `false`.
