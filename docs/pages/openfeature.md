# OpenFeature

**The Kotlin SDK does not ship an OpenFeature provider.** There is no
`openfeature` module, class, or dependency in this SDK. Evaluate flags directly
with the native API:

```kotlin
val flags = Client(currentUser)
flags.getFlag("new_checkout")          // → Boolean
flags.getFlagDetail("new_checkout")    // → FlagDetail(value, reason)
```

`getFlagDetail` returns a value plus a stable `reason` (LaunchDarkly
`variationDetail` parity) — the same information an OpenFeature `*Details`
resolution would carry. If you need OpenFeature semantics, map your own
`Provider` resolutions onto `getFlagDetail` yourself; see [Flags](flags.md) for
the `Reason` constants.
