package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestUtilitiesTest {
    // A running 100%-allocated experiment in universe "u" so an override on it can
    // surface through universe("u").assign() (assign iterates the loaded blob).
    private fun expsBlob(): Map<String, Any?> = mapOf(
        "universes" to mapOf("u" to mapOf("holdout_range" to null)),
        "experiments" to mapOf(
            "checkout_button" to mapOf(
                "universe" to "u",
                "allocationPct" to 10000,
                "salt" to "s",
                "status" to "running",
                "groups" to listOf(mapOf("name" to "control", "weight" to 10000, "params" to mapOf("color" to "blue"))),
            ),
        ),
    )

    private fun emptyFlags(): Map<String, Any?> = mapOf("gates" to emptyMap<String, Any?>(), "configs" to emptyMap<String, Any?>())

    // forTesting() builds a usable, no-network client with no API key. Defaults
    // apply when nothing is overridden.
    @Test
    fun forTestingNeedsNoNetworkOrKey() {
        Engine.forTesting().use { c ->
            assertFalse(c.getFlag("anything", mapOf("user_id" to "u1")))
            assertNull(c.getConfig("anything"))
            // No blob ⇒ not enrolled anywhere.
            val a = c.universe("anything").assign(mapOf("user_id" to "u1"))
            assertFalse(a.enrolled)
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

    // overrideExperiment makes universe(u).assign() enrol in the seeded group and
    // params (the override wins over the blob's real allocation).
    @Test
    fun overrideExperimentWins() {
        Engine.fromSnapshot(emptyFlags(), expsBlob()).use { c ->
            c.overrideExperiment("checkout_button", "treatment", mapOf("color" to "green"))
            val a = c.universe("u").assign(mapOf("user_id" to "u1"))
            assertTrue(a.enrolled)
            assertEquals("treatment", a.group)
            assertEquals("green", a.get("color"))
        }
    }

    // clearOverrides resets every override back to the blob/defaults.
    @Test
    fun clearOverridesResets() {
        Engine.fromSnapshot(emptyFlags(), expsBlob()).use { c ->
            c.overrideFlag("f", true)
            c.overrideConfig("cfg", "v")
            c.overrideExperiment("checkout_button", "treatment", mapOf("color" to "green"))

            c.clearOverrides()

            assertFalse(c.getFlag("f", emptyMap()))
            assertNull(c.getConfig("cfg"))
            // Override gone ⇒ falls back to the blob's real group (control).
            assertEquals("control", c.universe("u").assign(mapOf("user_id" to "u1")).group)
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
