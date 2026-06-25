package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestUtilitiesTest {
    // forTesting() builds a usable, no-network client with no API key. Defaults
    // apply when nothing is overridden.
    @Test
    fun forTestingNeedsNoNetworkOrKey() {
        Engine.forTesting().use { c ->
            assertFalse(c.getFlag("anything", mapOf("user_id" to "u1")))
            assertNull(c.getConfig("anything"))
            val r = c.getExperiment("anything", mapOf("user_id" to "u1"), null)
            assertFalse(r.inExperiment)
        }
    }

    // overrideFlag wins in getFlag.
    @Test
    fun overrideFlagWins() {
        Engine.forTesting().use { c ->
            c.overrideFlag("new_checkout", true)
            assertTrue(c.getFlag("new_checkout", emptyMap()))
            c.overrideFlag("new_checkout", false)
            assertFalse(c.getFlag("new_checkout", emptyMap()))
        }
    }

    // overrideConfig wins in getConfig, including a null value.
    @Test
    fun overrideConfigWins() {
        Engine.forTesting().use { c ->
            c.overrideConfig("billing_copy", "hello")
            assertEquals("hello", c.getConfig("billing_copy"))

            c.overrideConfig("limits", mapOf("max" to 5))
            assertEquals(mapOf("max" to 5), c.getConfig("limits"))

            // A null override is honoured (distinct from "no override").
            c.overrideConfig("maybe", null)
            assertNull(c.getConfig("maybe"))
        }
    }

    // overrideExperiment makes getExperiment return inExperiment with the seeded
    // group and params.
    @Test
    fun overrideExperimentWins() {
        Engine.forTesting().use { c ->
            c.overrideExperiment("checkout_button", "treatment", mapOf("color" to "green"))
            val r = c.getExperiment("checkout_button", emptyMap(), mapOf("color" to "blue"))
            assertTrue(r.inExperiment)
            assertEquals("treatment", r.group)
            assertEquals(mapOf("color" to "green"), r.params)
        }
    }

    // clearOverrides resets every override back to defaults.
    @Test
    fun clearOverridesResets() {
        Engine.forTesting().use { c ->
            c.overrideFlag("f", true)
            c.overrideConfig("cfg", "v")
            c.overrideExperiment("exp", "t", mapOf("a" to 1))

            c.clearOverrides()

            assertFalse(c.getFlag("f", emptyMap()))
            assertNull(c.getConfig("cfg"))
            assertFalse(c.getExperiment("exp", emptyMap(), null).inExperiment)
        }
    }

    // track() is a no-op on a forTesting() client — no key, no network, no throw.
    @Test
    fun trackIsNoOp() {
        Engine.forTesting().use { c ->
            c.track("u1", "purchase", mapOf("amount" to 49))
            c.track("u1", "view")
        }
    }
}
