# OpenFeature

**The Kotlin SDK does not ship an OpenFeature provider.** There is no
`openfeature` module, class, or dependency in this SDK — evaluate flags directly
with the native API:

```kotlin
val flags = Client(currentUser)
flags.getFlag("new_checkout")          // → Boolean
flags.getFlagDetail("new_checkout")    // → FlagDetail(value, reason)
```

`getFlagDetail` already returns a value + a stable `reason` (LaunchDarkly
`variationDetail` parity), which is the same shape an OpenFeature provider's
`*Details` resolution would expose — so a thin provider could be layered on top
later. None is bundled today.

If you need OpenFeature semantics, map your `Feature` / `Provider` resolutions
onto `getFlagDetail` (boolean) yourself; see [Flags](flags.md) for the `Reason`
constants.
