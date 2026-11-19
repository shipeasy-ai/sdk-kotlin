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
        .to("use the backup processor", mapOf("order_id" to order.id))
}
```

The chain:

- `causesThe(subject)` — names the thing affected (the consequence subject).
- `to(outcome, map)` — **terminal**: builds the wire event and fires the report,
  with the extras folded in (String / finite Number / Boolean only; truncated,
  capped at 20 keys). If you never call `to()`, **nothing is sent**. Calling
  `to()` twice is a no-op.
- `extras(map)` — standalone setter for the same context; reach for it only when
  you genuinely cannot pass the context inline.

### Where extras go in the chain

`causesThe(subject)` and `to(outcome)` are two halves of one sentence and must
stay adjacent, so fold the extras into the terminal:

```kotlin
// PREFERRED — the consequence reads as one sentence:
see(e).causesThe("checkout").to("use cached prices", mapOf("order_id" to order.id))
```

`to()` returns `Unit`, so extras cannot trail the terminal in Kotlin. And never
split the sentence with `extras()`:

```kotlin
// WON'T COMPILE — to() returns Unit:
// see(e).causesThe("checkout").to("use cached prices").extras(mapOf("order_id" to order.id))

// WRONG — extras wedged between the subject and the outcome. You read
// "checkout … order_id … use cached prices" and lose the consequence.
// see(e).causesThe("checkout").extras(mapOf("order_id" to order.id)).to("use cached prices")
```

### Linking a browser error to the server exception behind it

The browser SDK (`@shipeasy/sdk/client`) sends a per-request token on the
`X-SE-Correlation` header of every same-origin request. `AnonIdFilter` — the
same servlet filter that mints the bucketing cookie — reads it, and every
`see()` fired while serving that request carries it as `correlation_id`.
Shipeasy joins the browser's report and this server report on that token and
records the server exception as the browser error's cause, so the two are
triaged as one issue instead of two. Nothing to call once the filter is
registered:

```kotlin
@Bean
fun shipeasyAnonId() = FilterRegistrationBean(AnonIdFilter())
```

A malformed token is dropped, never sent; the token is cleared when the request
ends. On a non-servlet stack, set it from your own per-request hook with
`SeeContext.setCorrelationId(header)` and call `SeeContext.clear()` when the
request finishes.

## Non-exception problems — `seeViolation`

The name is a **stable fingerprint key** — put variable data in `extras()`, never
in the name:

```kotlin
import ai.shipeasy.seeViolation

seeViolation("negative_inventory")
    .to("clamp to zero", mapOf("sku" to sku))
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
