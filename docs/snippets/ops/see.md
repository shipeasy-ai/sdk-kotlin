Report a caught, handled error (or a non-exception "violation") to Shipeasy with
`see()` — fire-and-forget, never re-throws. Package-level, so it reports against
the SDK configured by `configure()`. Assumes `configure()` ran at startup — see
[Installation](../../pages/installation.md).

### Report a handled exception

```kotlin
import ai.shipeasy.see

try {
    charge(order)
} catch (e: Exception) {
    // .causesThe(subject)   what the error affects (e.g. "checkout")
    // .to(outcome)          the terminal — what you do about it; builds + fires once
    see(e).causesThe("checkout").to("use the backup processor")
    fallbackCharge(order)
}
```

### Attach context inline on `.to(outcome, map)`

```kotlin
import ai.shipeasy.see

try {
    charge(order)
} catch (e: Exception) {
    // .to(outcome, map)     PREFERRED: terminal + extras in one call. Structured
    //                       fields are sanitized (String / finite Number /
    //                       Boolean only; capped at 20 keys). The consequence
    //                       sentence stays whole and there is no ordering to
    //                       remember.
    see(e).causesThe("checkout").to("use cached prices", mapOf("order_id" to oid))
}
```

`.to` returns `Unit`, so extras cannot trail the terminal in Kotlin — the
inline form above is how you attach them. And never wedge `.extras(...)`
between `.causesThe` and `.to`: it splits the consequence sentence in half and
is hard to read.

```kotlin
// NEVER — the subject and the outcome must stay adjacent:
// see(e).causesThe("checkout").extras(mapOf("order_id" to oid)).to("use cached prices")
```

### Report a non-exception violation

```kotlin
import ai.shipeasy.seeViolation

// a bad state that isn't an exception — the name is a STABLE fingerprint; put
// variable data in .extras, never the name. .to() is the terminal.
seeViolation("missing_invoice").causesThe("billing").to("skip the dunning email")
```

### Mark an expected exception — report NOTHING

```kotlin
import ai.shipeasy.controlFlowException

try {
    parse(token)
} catch (e: NoSuchElementException) {
    // transmits nothing; .because(...) / .extras() are local-debug only
    controlFlowException(e).because("end of stream is expected")
}
```
