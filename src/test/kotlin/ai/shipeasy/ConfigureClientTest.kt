package ai.shipeasy

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the ergonomic front door: [configure] (global engine + attributes
 * transform) and the lightweight user-bound [Client].
 */
class ConfigureClientTest {
    @BeforeTest
    fun setup() {
        resetConfigureForTests()
        setDefaultClient(null)
    }

    @AfterTest
    fun teardown() {
        resetConfigureForTests()
        setDefaultClient(null)
    }

    // A gates blob with one fully-rolled gate so a bound Client evaluates true.
    private fun gatesBlob(): Map<String, Any?> = mapOf(
        "gates" to mapOf(
            "new_checkout" to mapOf(
                "enabled" to true,
                "rolloutPct" to 10000,
            ),
        ),
        "configs" to mapOf(
            "billing_copy" to mapOf("value" to "Pay now"),
        ),
    )

    @Test
    fun configureThenBoundClientGetFlagWorks() {
        // Seed the global engine from a snapshot so no network is needed.
        installEngineForTests(Engine.fromSnapshot(gatesBlob(), emptyMap()))

        val flags = Client(mapOf("user_id" to "u_123"))
        assertTrue(flags.getFlag("new_checkout"))
        assertFalse(flags.getFlag("missing_flag"))
        assertEquals("Pay now", flags.getConfig("billing_copy"))
        assertEquals("fallback", flags.getConfig("absent", default = "fallback"))
    }

    @Test
    fun realConfigurePathWiresGlobalEngineAndOverrides() {
        // The real configure() (localMode-equivalent: empty key + telemetry off)
        // returns the engine, registers it as the global, and is first-config-wins.
        val engine = configure(apiKey = "", disableTelemetry = true)
        assertSame(engine, currentEngine())
        // Second configure() returns the SAME engine (idempotent).
        assertSame(engine, configure(apiKey = "other"))
        // Seed an override on the global engine; the bound Client reads it.
        engine.overrideFlag("new_checkout", true)
        assertTrue(Client(mapOf("user_id" to "u_1")).getFlag("new_checkout"))
    }

    @Test
    fun attributesTransformIsApplied() {
        // A gate rolled out to only 50% — assert the transform's mapped user_id is
        // what gets bucketed by comparing the bound Client against the engine
        // called with the explicitly mapped attribute bag.
        val blob = mapOf(
            "gates" to mapOf(
                "partial" to mapOf("enabled" to true, "rolloutPct" to 5000),
            ),
        )
        val engine = Engine.fromSnapshot(blob, emptyMap())
        installEngineForTests(engine) { raw ->
            val u = raw as RawUser
            mapOf("user_id" to u.id, "plan" to u.plan)
        }

        val raw = RawUser(id = "user_42", plan = "pro")
        val viaClient = Client(raw).getFlag("partial")
        val viaEngine = engine.getFlag("partial", mapOf("user_id" to "user_42", "plan" to "pro"))
        assertEquals(viaEngine, viaClient)

        // The stored attribute bag is the mapped one, not the raw object.
        assertEquals(mapOf("user_id" to "user_42", "plan" to "pro"), Client(raw).attributes)
    }

    @Test
    fun identityTransformUsesUserMapVerbatim() {
        installEngineForTests(Engine.fromSnapshot(gatesBlob(), emptyMap()))
        // No transform configured ⇒ identity ⇒ the map IS the attribute bag.
        val c = Client(mapOf("user_id" to "u_1", "country" to "US"))
        assertEquals(mapOf("user_id" to "u_1", "country" to "US"), c.attributes)
    }

    @Test
    fun constructingClientBeforeConfigureFailsLoudly() {
        // No configure()/global engine installed.
        assertFailsWith<IllegalStateException> {
            Client(mapOf("user_id" to "u_1"))
        }
    }

    @Test
    fun getExperimentForwardsBoundUser() {
        val engine = Engine.forTesting()
        engine.overrideExperiment("checkout_button", group = "treatment", params = mapOf("color" to "green"))
        installEngineForTests(engine)
        val r = Client(mapOf("user_id" to "u_1")).getExperiment("checkout_button", null)
        assertTrue(r.inExperiment)
        assertEquals("treatment", r.group)
        assertEquals(mapOf("color" to "green"), r.params)
    }

    @Test
    fun getKillswitchForwardsToEngine() {
        val blob = mapOf(
            "gates" to emptyMap<String, Any?>(),
            "killswitches" to mapOf(
                "payments" to mapOf("killed" to true),
                "search" to mapOf(
                    "killed" to false,
                    "switches" to mapOf("beta" to true),
                ),
            ),
        )
        installEngineForTests(Engine.fromSnapshot(blob, emptyMap()))
        val c = Client(mapOf("user_id" to "u_1"))
        assertTrue(c.getKillswitch("payments"))
        assertFalse(c.getKillswitch("search"))
        assertTrue(c.getKillswitch("search", "beta"))
        assertFalse(c.getKillswitch("search", "unknown"))
        assertFalse(c.getKillswitch("absent"))
    }

    private data class RawUser(val id: String, val plan: String)
}
