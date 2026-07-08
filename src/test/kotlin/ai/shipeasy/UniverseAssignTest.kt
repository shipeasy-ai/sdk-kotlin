package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Universe-first assignment (the mutual-exclusion pool model, doc 20 §B).
 *
 * `engine.universe(name).assign(user)` returns an [Assignment]: the ≤1 experiment
 * the unit landed in within the universe, its variant, and resolved params
 * (variant override ?? universe default ?? fallback). These specs lock the merge
 * (§B2), the not-enrolled defaults path, pooled mutual exclusion (§B4), reserved
 * headroom (§B5), and the holdout gate (§B3). Mirrors the TS
 * `src/__tests__/universe-assign.test.ts`. Blobs are seeded directly (offline
 * `fromSnapshot`, no network).
 */
class UniverseAssignTest {

    private val MOD = 10000
    private fun universeSeg(universe: String, uid: String): Int = Murmur3.bucket("$universe:$uid", MOD)

    private fun emptyFlags(): Map<String, Any?> = mapOf("gates" to emptyMap<String, Any?>(), "configs" to emptyMap<String, Any?>())

    private fun engine(flags: Map<String, Any?>, exps: Map<String, Any?>): Engine =
        Engine.fromSnapshot(flags, exps)

    // ---- param merge (§B2) ----

    @Test
    fun variantOverrideWinsUnsetInheritsDefaultUnknownFallsBack() {
        // Universe owns button_color=red, size=1. The one running experiment's
        // assigned variant overrides only button_color.
        val exps = mapOf(
            "universes" to mapOf(
                "u" to mapOf(
                    "holdout_range" to null,
                    "param_schema" to listOf(
                        mapOf("name" to "button_color", "type" to "string", "default" to "red"),
                        mapOf("name" to "size", "type" to "int", "default" to 1),
                    ),
                ),
            ),
            "experiments" to mapOf(
                "exp" to mapOf(
                    "universe" to "u", "allocationPct" to 10000, "salt" to "s", "status" to "running",
                    "groups" to listOf(mapOf("name" to "treatment", "weight" to 10000, "params" to mapOf("button_color" to "blue"))),
                ),
            ),
        )
        val a = engine(emptyFlags(), exps).universe("u").assign(mapOf("user_id" to "u1"))
        assertTrue(a.enrolled)
        assertEquals("treatment", a.group)
        // Overridden by the variant.
        assertEquals("blue", a.get("button_color"))
        // Not overridden → inherited from the universe default.
        assertEquals(1, a.get("size"))
        // Absent everywhere → the caller's fallback.
        assertEquals("fb", a.get("missing", "fb"))
    }

    // ---- not enrolled still gets universe defaults ----

    @Test
    fun notEnrolledResolvesToUniverseDefault() {
        val exps = mapOf(
            "universes" to mapOf(
                "u" to mapOf(
                    "holdout_range" to null,
                    "param_schema" to listOf(mapOf("name" to "button_color", "type" to "string", "default" to "red")),
                ),
            ),
            "experiments" to mapOf(
                "exp" to mapOf(
                    "universe" to "u", "allocationPct" to 0, "salt" to "s", "status" to "running",
                    "groups" to listOf(mapOf("name" to "treatment", "weight" to 10000, "params" to mapOf("button_color" to "blue"))),
                ),
            ),
        )
        val a = engine(emptyFlags(), exps).universe("u").assign(mapOf("user_id" to "u1"))
        assertFalse(a.enrolled)
        assertNull(a.group)
        // Not enrolled → universe default, not the variant override.
        assertEquals("red", a.get("button_color"))
    }

    // ---- pooled mutual exclusion (§B4) ----

    @Test
    fun pooledMutualExclusionOverManyUsers() {
        // Two experiments in ONE universe, hashVersion 2, disjoint pool slices:
        //   A = [0, 4000), B = [4000, 8000). Segment >= 8000 is unallocated headroom.
        val exps = mapOf(
            "universes" to mapOf("u" to mapOf("holdout_range" to null)),
            "experiments" to mapOf(
                "expA" to mapOf(
                    "universe" to "u", "hashVersion" to 2, "poolOffsetBp" to 0, "poolSizeBp" to 4000,
                    "allocationPct" to 10000, "salt" to "sA", "status" to "running",
                    "groups" to listOf(mapOf("name" to "A", "weight" to 10000, "params" to emptyMap<String, Any?>())),
                ),
                "expB" to mapOf(
                    "universe" to "u", "hashVersion" to 2, "poolOffsetBp" to 4000, "poolSizeBp" to 4000,
                    "allocationPct" to 10000, "salt" to "sB", "status" to "running",
                    "groups" to listOf(mapOf("name" to "B", "weight" to 10000, "params" to emptyMap<String, Any?>())),
                ),
            ),
        )
        val e = engine(emptyFlags(), exps)
        var inA = 0; var inB = 0; var neither = 0
        for (i in 0 until 400) {
            val uid = "u$i"
            val a = e.universe("u").assign(mapOf("user_id" to uid))
            val seg = universeSeg("u", uid)
            when (a.name) {
                "expA" -> { inA++; assertTrue(seg < 4000) }
                "expB" -> { inB++; assertTrue(seg in 4000..7999) }
                else -> { neither++; assertFalse(a.enrolled); assertTrue(seg >= 8000) }
            }
        }
        // The partition is real: all three buckets are populated over 400 users.
        assertTrue(inA > 0)
        assertTrue(inB > 0)
        assertTrue(neither > 0)
        assertEquals(400, inA + inB + neither)
    }

    // ---- reserved headroom (§B5) ----

    @Test
    fun reservedHeadroomLeavesTailUnassigned() {
        // 100% allocation, groups summing to 5000 with reservedHeadroomBp 5000:
        // units whose group hash falls in the reserved tail are left not-enrolled.
        val exps = mapOf(
            "universes" to mapOf("u" to mapOf("holdout_range" to null)),
            "experiments" to mapOf(
                "exp" to mapOf(
                    "universe" to "u", "allocationPct" to 10000, "reservedHeadroomBp" to 5000,
                    "salt" to "s", "status" to "running",
                    "groups" to listOf(mapOf("name" to "control", "weight" to 5000, "params" to emptyMap<String, Any?>())),
                ),
            ),
        )
        val e = engine(emptyFlags(), exps)
        var enrolled = 0; var reserved = 0
        for (i in 0 until 400) {
            val a = e.universe("u").assign(mapOf("user_id" to "u$i"))
            if (a.enrolled) enrolled++ else reserved++
        }
        // Both populated: allocation is 100% yet the reserved tail carves out ~half.
        assertTrue(enrolled > 0)
        assertTrue(reserved > 0)
    }

    // ---- holdout gate (§B3) ----

    @Test
    fun holdoutGateForcesHoldout() {
        val flags = mapOf(
            "gates" to mapOf(
                // enabled, 100% rollout, no rules → passes for every identified unit.
                "hg" to mapOf("rules" to emptyList<Any?>(), "rolloutPct" to 10000, "salt" to "hg", "enabled" to 1),
            ),
        )
        val exps = mapOf(
            "universes" to mapOf("u" to mapOf("holdout_range" to null)),
            "experiments" to mapOf(
                "exp" to mapOf(
                    "universe" to "u", "holdoutGate" to "hg", "allocationPct" to 10000, "salt" to "s", "status" to "running",
                    "groups" to listOf(mapOf("name" to "treatment", "weight" to 10000, "params" to emptyMap<String, Any?>())),
                ),
            ),
        )
        val a = engine(flags, exps).universe("u").assign(mapOf("user_id" to "u1"))
        assertFalse(a.enrolled)
        assertNull(a.group)
    }

    // ---- native ShipeasyClient (remote-eval) universe assign ----

    @Test
    fun clientAssignReadsCachedEdgeResponse() = kotlinx.coroutines.runBlocking {
        // The edge returns per-exp `universe` + `universes.defaults`, and pre-merges
        // universe defaults into each entry's params. universe(name).assign() reads
        // that cached response: enrolled → variant params; not enrolled → defaults.
        val stub: ClientTransport = { path, _ ->
            if (path.startsWith("/sdk/evaluate")) {
                200 to (
                    """{"flags":{},"configs":{},"killswitches":{},""" +
                        """"experiments":{"exp":{"inExperiment":true,"group":"treatment","params":{"button_color":"blue","size":1},"universe":"u"}},""" +
                        """"universes":{"u":{"defaults":{"button_color":"red","size":1}},"empty":{"defaults":{"tone":"soft"}}}}"""
                    ).toByteArray()
            } else {
                200 to ByteArray(0)
            }
        }
        val c = ShipeasyClient(clientKey = "pk", store = InMemoryAnonStore(), disableTelemetry = true, transport = stub)
        c.identify(mapOf("user_id" to "u1"))

        val a = c.universe("u").assign()
        assertTrue(a.enrolled)
        assertEquals("exp", a.name)
        assertEquals("treatment", a.group)
        // Edge pre-merged params: variant override + inherited default.
        // (JSON numbers decode to Long via kotlinx.serialization.)
        assertEquals("blue", a.get("button_color"))
        assertEquals(1L, a.get("size"))

        // A universe with no enrolled experiment resolves to its defaults.
        val b = c.universe("empty").assign()
        assertFalse(b.enrolled)
        assertNull(b.group)
        assertEquals("soft", b.get("tone"))
    }
}
