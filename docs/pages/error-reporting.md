# Error reporting — `see()`

The Kotlin SDK ships the `see()` structured-error surface (parity with
`@shipeasy/sdk` and the Python reference). It reports a **handled** error along
with its product *consequence* — not just a stack trace — fire-and-forget to
`/collect`. Reporting never blocks and never throws into your request path.

> If you don't know the consequence of an exception, don't catch it.

## Package-level `see()`

`see()` reports against the SDK configured by `configure()` — no object to pass:

```kotlin
import ai.shipeasy.see

try {
    chargeCard(order)
} catch (e: Exception) {
    see(e)
        .causesThe("checkout")
        .extras(mapOf("order_id" to order.id))
        .to("use the backup processor")
}
```

The chain:

- `causesThe(subject)` — names the thing affected (the consequence subject).
- `extras(map)` — structured context (String / finite Number / Boolean only;
  truncated, capped at 20 keys).
- `to(outcome)` — **terminal**: builds the wire event and fires the report.
  If you never call `to()`, **nothing is sent**. Calling `to()` twice is a no-op.

`causesThe()` and `extras()` may be called in any order before `to()`. You can
also pass the extras inline on the terminal — `to(outcome, map)` is equivalent to
a final `extras(...)` (it folds over any earlier `extras()`, later wins), so there
is no ordering to remember:

```kotlin
see(e).causesThe("checkout").to("use the backup processor", mapOf("order_id" to order.id))
```

## Non-exception problems — `seeViolation`

The name is a **stable fingerprint key** — put variable data in `extras()`, never
in the name:

```kotlin
import ai.shipeasy.seeViolation

seeViolation("negative_inventory")
    .extras(mapOf("sku" to sku))
    .to("clamp to zero")
```

## Expected control flow — `controlFlowException`

Mark an exception as expected control flow; it reports **nothing**. `.extras()`
is stored for local debugging only.

```kotlin
import ai.shipeasy.controlFlowException

controlFlowException(e).because("retryable timeout — handled by the retry loop")
```

## Notes

- A per-process spam guard collapses identical reports within a 30s window and
  caps total sends per process.
- Configured `privateAttributes` are stripped from `extras()`.
- `env` (from `configure()`) is tagged onto every event.
- Calling `see()` before `configure()` ran logs a warning and is a no-op.
