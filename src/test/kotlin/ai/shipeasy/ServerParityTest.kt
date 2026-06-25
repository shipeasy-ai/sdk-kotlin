package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for three server-SDK parity additions:
 *   Feature A — private attributes (stripped from track() egress)
 *   Feature B — manual exposure (logExposure)
 *   Feature C — sticky bucketing (StickyBucketStore + InMemoryStickyStore)
 *
 * The track()/logExposure egress is network-bound, so Feature A is exercised at
 * the pure-function boundary (stripPrivate via reflection on a real client) and
 * the no-op contract is asserted via localMode. Feature C is exercised end-to-end
 * through the offline fromSnapshot client (no network needed).
 */
class ServerParityTest {

    // A running 50/50 experiment, 100% allocated, no holdout.
    private fun expsBlob(over: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
        "universes" to mapOf("u" to mapOf("holdout_range" to null)),
        "experiments" to mapOf(
            "exp" to (mapOf(
                "universe" to "u",
                "allocationPct" to 10000,
                "salt" to "salt_abcdef",
                "status" to "running",
                "groups" to listOf(
                    mapOf("name" to "control", "weight" to 5000, "params" to mapOf("v" to "a")),
                    mapOf("name" to "treatment", "weight" to 5000, "params" to mapOf("v" to "b")),
                ),
            ) + over),
        ),
    )

    private fun emptyFlags(): Map<String, Any?> = mapOf("gates" to emptyMap<String, Any?>(), "configs" to emptyMap<String, Any?>())

    // ---- Feature A: private attributes ----

    // Listed keys are dropped; everything else is preserved. Reflection is used
    // because stripPrivate is a private boundary helper (track()'s only egress is
    // the network).
    @Suppress("UNCHECKED_CAST")
    private fun strip(client: Engine, props: Map<String, Any?>?): Map<String, Any?>? {
        val m = Engine::class.java.getDeclaredMethod("stripPrivate", Map::class.java)
        m.isAccessible = true
        return m.invoke(client, props) as Map<String, Any?>?
    }

    @Test
    fun privateAttributesStrippedFromTrackProps() {
        Engine(apiKey = "", disableTelemetry = true, privateAttributes = listOf("email", "ssn"), localMode = true).use { c ->
            val out = strip(c, mapOf("email" to "a@b.com", "ssn" to "123", "plan" to "pro"))
            assertEquals(mapOf("plan" to "pro"), out)
        }
    }

    @Test
    fun noPrivateAttributesIsPassThrough() {
        Engine(apiKey = "", disableTelemetry = true, localMode = true).use { c ->
            val props = mapOf("email" to "a@b.com", "plan" to "pro")
            assertEquals(props, strip(c, props))
            assertNull(strip(c, null))
        }
    }

    @Test
    fun privateAttributesLeaveNonPrivatePropsAndNullProps() {
        Engine(apiKey = "", disableTelemetry = true, privateAttributes = listOf("email"), localMode = true).use { c ->
            // null props remain null (nothing to strip)
            assertNull(strip(c, null))
            // a bag without any private key is unchanged
            assertEquals(mapOf("plan" to "pro"), strip(c, mapOf("plan" to "pro")))
        }
    }

    @Test
    fun trackIsNoOpInLocalMode() {
        // localMode track() must never reach the network — assert it does not throw
        // even though no server exists.
        Engine.fromSnapshot(emptyFlags(), expsBlob()).use { c ->
            c.track("u1", "purchase", mapOf("email" to "a@b.com", "amount" to 10))
        }
    }

    // ---- Feature B: manual exposure ----

    @Test
    fun logExposureNoOpWhenNotEnrolled() {
        // allocation 0% ⇒ nobody enrolled ⇒ logExposure is a no-op (no throw).
        Engine.fromSnapshot(emptyFlags(), expsBlob(mapOf("allocationPct" to 0))).use { c ->
            assertFalse(c.getExperiment("exp", mapOf("user_id" to "u1"), null).inExperiment)
            c.logExposure("u1", "exp") // no enrolment → no-op, must not throw
        }
    }

    @Test
    fun logExposureNoOpInLocalMode() {
        // fromSnapshot is localMode: enrolled, but logExposure must still no-op on
        // the network (no throw).
        Engine.fromSnapshot(emptyFlags(), expsBlob()).use { c ->
            assertTrue(c.getExperiment("exp", mapOf("user_id" to "u1"), null).inExperiment)
            c.logExposure("u1", "exp")
        }
    }

    @Test
    fun logExposureNoOpForUnknownExperiment() {
        Engine.fromSnapshot(emptyFlags(), expsBlob()).use { c ->
            c.logExposure("u1", "does_not_exist") // not enrolled → no-op
        }
    }

    // ---- Feature C: sticky bucketing ----

    @Test
    fun noStoreIsDeterministic() {
        Engine.fromSnapshot(emptyFlags(), expsBlob()).use { c ->
            val a = c.getExperiment("exp", mapOf("user_id" to "u1"), null)
            val b = c.getExperiment("exp", mapOf("user_id" to "u1"), null)
            assertEquals(a.group, b.group)
        }
    }

    @Test
    fun freshPickIsPersisted() {
        val store = InMemoryStickyStore()
        Engine.fromSnapshot(emptyFlags(), expsBlob(), store).use { c ->
            val r = c.getExperiment("exp", mapOf("user_id" to "u1"), null)
            assertTrue(r.inExperiment)
            val entry = store.get("u1")?.get("exp")
            assertNotNull(entry)
            assertEquals(r.group, entry.group)
            assertEquals("salt_abc", entry.salt8) // "salt_abcdef".take(8)
        }
    }

    @Test
    fun weightChangeKeepsStickiedUserInOriginalGroup() {
        val store = InMemoryStickyStore()
        val original = Engine.fromSnapshot(emptyFlags(), expsBlob(), store).use {
            it.getExperiment("exp", mapOf("user_id" to "u1"), null).group
        }
        // Reweight so the deterministic pick would flip; the stickied user stays.
        val reweighted = expsBlob(
            mapOf(
                "groups" to listOf(
                    mapOf("name" to "control", "weight" to if (original == "control") 1 else 9999, "params" to mapOf("v" to "a")),
                    mapOf("name" to "treatment", "weight" to if (original == "control") 9999 else 1, "params" to mapOf("v" to "b")),
                ),
            ),
        )
        Engine.fromSnapshot(emptyFlags(), reweighted, store).use { c ->
            assertEquals(original, c.getExperiment("exp", mapOf("user_id" to "u1"), null).group)
        }
    }

    @Test
    fun allocationShrinkKeepsEnrolledButDeniesNew() {
        val store = InMemoryStickyStore()
        Engine.fromSnapshot(emptyFlags(), expsBlob(), store).use { c ->
            assertTrue(c.getExperiment("exp", mapOf("user_id" to "u1"), null).inExperiment)
        }
        val shrunk = expsBlob(mapOf("allocationPct" to 0))
        Engine.fromSnapshot(emptyFlags(), shrunk, store).use { c ->
            // already-stickied unit stays in even at 0% allocation
            assertTrue(c.getExperiment("exp", mapOf("user_id" to "u1"), null).inExperiment)
            // a brand-new unit is denied
            assertFalse(c.getExperiment("exp", mapOf("user_id" to "u_new"), null).inExperiment)
        }
    }

    @Test
    fun saltChangeReshufflesStoredPrefix() {
        val store = InMemoryStickyStore()
        Engine.fromSnapshot(emptyFlags(), expsBlob(), store).use {
            it.getExperiment("exp", mapOf("user_id" to "u1"), null)
        }
        assertEquals("salt_abc", store.get("u1")?.get("exp")?.salt8)

        Engine.fromSnapshot(emptyFlags(), expsBlob(mapOf("salt" to "zzzz_newsalt")), store).use {
            it.getExperiment("exp", mapOf("user_id" to "u1"), null)
        }
        assertEquals("zzzz_new", store.get("u1")?.get("exp")?.salt8)
    }

    @Test
    fun vanishedStoredGroupRebuckets() {
        // Seed a store entry pointing at a group that no longer exists, with the
        // CURRENT salt8 → must fall through to a real re-bucket (an existing group)
        // rather than returning the stale group.
        val store = InMemoryStickyStore(mapOf("u1" to mapOf("exp" to StickyEntry("ghost", "salt_abc"))))
        Engine.fromSnapshot(emptyFlags(), expsBlob(), store).use { c ->
            val r = c.getExperiment("exp", mapOf("user_id" to "u1"), null)
            assertTrue(r.inExperiment)
            assertTrue(r.group == "control" || r.group == "treatment")
            // overwrite happened with a valid group
            assertEquals(r.group, store.get("u1")?.get("exp")?.group)
        }
    }

    @Test
    fun staleSaltEntryIsIgnoredAndOverwritten() {
        // A store entry with a stale salt8 must be ignored (re-bucket + overwrite).
        val store = InMemoryStickyStore(mapOf("u1" to mapOf("exp" to StickyEntry("treatment", "OLDSALT8"))))
        Engine.fromSnapshot(emptyFlags(), expsBlob(), store).use { c ->
            c.getExperiment("exp", mapOf("user_id" to "u1"), null)
            assertEquals("salt_abc", store.get("u1")?.get("exp")?.salt8)
        }
    }
}
