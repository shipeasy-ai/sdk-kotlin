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

## Named per-switch override key

A kill switch can carry named per-key override **switches**. Pass `switchKey` to
read one specific named switch: `true` when that named override is on.

```kotlin
flags.getKillswitch("payments", switchKey = "eu_region")   // → Boolean
```

If the named switch key isn't configured for this kill switch, the read **falls
back to the kill switch's top-level value** (the plain `getKillswitch(name)`
result).

## Semantics

- Without `switchKey`: returns `true` when the whole kill switch is killed.
- With `switchKey`: returns `true` when that specific per-key override switch is
  on; an unconfigured key falls back to the top-level value.
- Unknown kill switches return `false`.
