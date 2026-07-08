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

    // A running experiment in universe "u" so an override on it surfaces through
    // universe("u").assign() (assign iterates the loaded experiments blob).
    private fun expsBlob(): Map<String, Any?> = mapOf(
        "universes" to mapOf("u" to mapOf("holdout_range" to null)),
        "experiments" to mapOf(
            "checkout_button" to mapOf(
                "universe" to "u",
                "allocationPct" to 10000,
                "salt" to "s",
                "status" to "running",
                "groups" to listOf(mapOf("name" to "control", "weight" to 10000, "params" to emptyMap<String, Any?>())),
            ),
        ),
    )

    @Test
    fun assignForwardsBoundUser() {
        val engine = Engine.fromSnapshot(mapOf("gates" to emptyMap<String, Any?>()), expsBlob())
        engine.overrideExperiment("checkout_button", group = "treatment", params = mapOf("color" to "green"))
        installEngineForTests(engine)
        val a = Client(mapOf("user_id" to "u_1")).universe("u").assign()
        assertTrue(a.enrolled)
        assertEquals("treatment", a.group)
        assertEquals("green", a.get("color"))
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

    @Test
    fun boundClientTrackForwardsBoundUserId() {
        // Non-localMode engine so track() reaches eventSender; capture the wire body.
        val sent = captureEvents { engine ->
            installEngineForTests(engine)
            Client(mapOf("user_id" to "u_77", "plan" to "pro")).track("checkout", mapOf("amount" to 9))
        }
        val ev = sent.single()
        assertEquals("metric", ev["type"])
        assertEquals("checkout", ev["event_name"])
        assertEquals("u_77", ev["user_id"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(mapOf("amount" to 9.0), (ev["properties"] as Map<String, Any?>))
    }

    @Test
    fun boundClientTrackFallsBackToAnonymousId() {
        val sent = captureEvents { engine ->
            installEngineForTests(engine)
            Client(mapOf("anonymous_id" to "anon_5")).track("view")
        }
        assertEquals("anon_5", sent.single()["user_id"])
    }

    @Test
    fun boundClientTrackNoOpsWithoutUnit() {
        val sent = captureEvents { engine ->
            installEngineForTests(engine)
            Client(mapOf("plan" to "pro")).track("view") // no user_id / anonymous_id
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun boundClientAssignAutoLogsExposure() {
        // universe(u).assign() auto-logs a single exposure when enrolled; an
        // override makes the user "enrolled" so the exposure event fires.
        val sent = captureEvents { engine ->
            // Seed a real running experiment in universe "u" so assign finds it.
            engine.applyDataForTest(
                flags = mapOf("gates" to emptyMap<String, Any?>()),
                experiments = mapOf(
                    "universes" to mapOf("u" to mapOf("holdout_range" to null)),
                    "experiments" to mapOf(
                        "price_test" to mapOf(
                            "universe" to "u", "allocationPct" to 10000, "salt" to "s", "status" to "running",
                            "groups" to listOf(mapOf("name" to "control", "weight" to 10000, "params" to emptyMap<String, Any?>())),
                        ),
                    ),
                ),
            )
            engine.overrideExperiment("price_test", group = "treatment", params = null)
            installEngineForTests(engine)
            Client(mapOf("user_id" to "u_9")).universe("u").assign()
        }
        val ev = sent.single()
        assertEquals("exposure", ev["type"])
        assertEquals("price_test", ev["experiment"])
        assertEquals("treatment", ev["group"])
        assertEquals("u_9", ev["user_id"])
    }

    // ---- helpers ----

    // Build a non-localMode engine, install a capturing eventSender, run [body],
    // and return the captured /collect events. Telemetry disabled (no per-eval beacon).
    private fun captureEvents(body: (Engine) -> Unit): List<Map<String, Any?>> {
        val sent = mutableListOf<Map<String, Any?>>()
        val engine = Engine("srv_key", baseUrl = "https://e.x", disableTelemetry = true)
            .also { it.initialized = true }
        engine.eventSender = { wire -> parseEvents(wire, sent) }
        body(engine)
        return sent
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEvents(wire: ByteArray, into: MutableList<Map<String, Any?>>) {
        val root = parseJson(String(wire)) as Map<String, Any?>
        for (e in root["events"] as List<*>) into.add(e as Map<String, Any?>)
    }

    private fun parseJson(s: String): Any? {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(s)
        return jsonToAny(el)
    }

    private fun jsonToAny(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonObject -> e.mapValues { jsonToAny(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.map { jsonToAny(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (e.isString) e.content
            else e.content.toBooleanStrictOrNull() ?: e.content.toDoubleOrNull() ?: e.content
    }

    private data class RawUser(val id: String, val plan: String)
}
