package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the competitor-parity additions: getFlag/getConfig defaults
 * (Feature A), getFlagDetail reasons (Feature B), onChange listeners
 * (Feature C), and the offline fromSnapshot data source (Feature D).
 */
class ParityTest {

    // A gates blob with one fully-rolled enabled gate, one disabled gate.
    private fun gatesBlob(): Map<String, Any?> = mapOf(
        "gates" to mapOf(
            "on_gate" to mapOf("enabled" to true, "rolloutPct" to 10000, "salt" to ""),
            "off_gate" to mapOf("enabled" to false, "rolloutPct" to 10000, "salt" to ""),
        ),
        "configs" to mapOf(
            "billing_copy" to mapOf("value" to "Pay now"),
        ),
    )

    // ---- Feature A: default on getFlag / getConfig ----

    // default is returned ONLY when the flag can't be evaluated (not-ready /
    // not-found) — never for a flag that legitimately evaluates to false.
    @Test
    fun getFlagDefaultOnlyOnNotFoundOrNotReady() {
        // not initialized → CLIENT_NOT_READY → default returned
        Client(apiKey = "", disableTelemetry = true, localMode = true).use { c ->
            // localMode but NOT marked initialized: blob is null → CLIENT_NOT_READY
            assertTrue(c.getFlag("missing", mapOf("user_id" to "u1"), default = true))
            assertFalse(c.getFlag("missing", mapOf("user_id" to "u1"), default = false))
        }

        Client.fromSnapshot(gatesBlob(), emptyMap()).use { c ->
            // not-found → default returned
            assertTrue(c.getFlag("nope", mapOf("user_id" to "u1"), default = true))
            // present + evaluates false (disabled) → default NOT used, real false
            assertFalse(c.getFlag("off_gate", mapOf("user_id" to "u1"), default = true))
            // present + evaluates true → real true
            assertTrue(c.getFlag("on_gate", mapOf("user_id" to "u1"), default = false))
            // 2-arg call site still compiles + returns false for a missing flag
            assertFalse(c.getFlag("nope", mapOf("user_id" to "u1")))
        }
    }

    // getConfig default returned when key absent; present value wins; null
    // override still honoured over the default.
    @Test
    fun getConfigDefault() {
        Client.fromSnapshot(gatesBlob(), emptyMap()).use { c ->
            assertEquals("fallback", c.getConfig("nope", default = "fallback"))
            assertEquals("Pay now", c.getConfig("billing_copy", default = "fallback"))
            // no-arg / no-default still null for missing
            assertNull(c.getConfig("nope"))
            // null override wins over default
            c.overrideConfig("nope", null)
            assertNull(c.getConfig("nope", default = "fallback"))
        }
    }

    // ---- Feature B: getFlagDetail reasons ----

    @Test
    fun flagDetailReasons() {
        // CLIENT_NOT_READY: localMode client never seeded with a blob
        Client(apiKey = "", disableTelemetry = true, localMode = true).use { c ->
            val d = c.getFlagDetail("anything", mapOf("user_id" to "u1"))
            assertEquals(Reason.CLIENT_NOT_READY, d.reason)
            assertFalse(d.value)
        }

        Client.fromSnapshot(gatesBlob(), emptyMap()).use { c ->
            // FLAG_NOT_FOUND
            val nf = c.getFlagDetail("nope", mapOf("user_id" to "u1"))
            assertEquals(Reason.FLAG_NOT_FOUND, nf.reason)
            assertFalse(nf.value)

            // OFF (gate present but disabled)
            val off = c.getFlagDetail("off_gate", mapOf("user_id" to "u1"))
            assertEquals(Reason.OFF, off.reason)
            assertFalse(off.value)

            // RULE_MATCH (fully-rolled, enabled)
            val on = c.getFlagDetail("on_gate", mapOf("user_id" to "u1"))
            assertEquals(Reason.RULE_MATCH, on.reason)
            assertTrue(on.value)

            // OVERRIDE short-circuits before telemetry/eval
            c.overrideFlag("off_gate", true)
            val ov = c.getFlagDetail("off_gate", mapOf("user_id" to "u1"))
            assertEquals(Reason.OVERRIDE, ov.reason)
            assertTrue(ov.value)
            c.clearOverrides()
        }
    }

    // DEFAULT reason: gate enabled but a fractional rollout the user falls
    // outside of → evaluates false with reason DEFAULT (not OFF).
    @Test
    fun flagDetailDefaultReason() {
        val blob = mapOf(
            "gates" to mapOf(
                // 0% rollout, enabled: always false for an identified unit
                "ramp" to mapOf("enabled" to true, "rolloutPct" to 0, "salt" to ""),
            ),
        )
        Client.fromSnapshot(blob, emptyMap()).use { c ->
            val d = c.getFlagDetail("ramp", mapOf("user_id" to "u1"))
            assertEquals(Reason.DEFAULT, d.reason)
            assertFalse(d.value)
        }
    }

    // ---- Feature C: onChange ----

    // Listener fires when new data is applied; unsubscribe stops it.
    @Test
    fun onChangeFiresAndUnsubscribes() {
        Client.fromSnapshot(gatesBlob(), emptyMap()).use { c ->
            var hits = 0
            val unsub = c.onChange { hits++ }

            c.applyDataForTest(gatesBlob(), emptyMap())
            assertEquals(1, hits)

            c.applyDataForTest(gatesBlob(), emptyMap())
            assertEquals(2, hits)

            unsub()
            c.applyDataForTest(gatesBlob(), emptyMap())
            assertEquals(2, hits) // no further fire after unsubscribe
        }
    }

    // A throwing listener does not break notification of the others.
    @Test
    fun onChangeListenerThrowIsIsolated() {
        Client.fromSnapshot(gatesBlob(), emptyMap()).use { c ->
            var good = 0
            c.onChange { throw RuntimeException("boom") }
            c.onChange { good++ }
            c.applyDataForTest(gatesBlob(), emptyMap())
            assertEquals(1, good)
        }
    }

    // ---- Feature D: fromSnapshot evaluates with no network ----

    @Test
    fun fromSnapshotEvaluatesOffline() {
        val flags = mapOf(
            "gates" to mapOf(
                "new_checkout" to mapOf("enabled" to true, "rolloutPct" to 10000, "salt" to ""),
            ),
            "configs" to mapOf(
                "limits" to mapOf("value" to mapOf("max" to 5L)),
            ),
        )
        Client.fromSnapshot(flags, emptyMap()).use { c ->
            // real eval against the seeded blob — no network, no init() needed
            assertTrue(c.getFlag("new_checkout", mapOf("user_id" to "u_123")))
            assertEquals(mapOf("max" to 5L), c.getConfig("limits"))
            // overrides still apply on top of a snapshot client
            c.overrideFlag("new_checkout", false)
            assertFalse(c.getFlag("new_checkout", mapOf("user_id" to "u_123")))
        }
    }
}
