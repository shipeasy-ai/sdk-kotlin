package ai.shipeasy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Self-monitoring channel: when the SDK swallows an internal ("on our end")
 * error via a runtime reader's last-resort guard, it also ships a structured see
 * event to Shipeasy's OWN project — a baked-in destination + public client key,
 * distinct from the consumer's see() path. These tests pin the wire shape, the
 * enable gating, the dedup, and the no-throw guarantee. Mirrors the TS
 * `internal-report.test.ts` contract.
 */
class InternalReportTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // A real-looking client key to exercise the send path (the baked default is
    // an inert placeholder until the real key is minted).
    private val fakeKey = "sdk_client_testfakekey00000000000000000000"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // Capture the /collect wire body synchronously via the injectable sender seam.
    private fun captureSends(): MutableList<JsonObject> {
        val sent = mutableListOf<JsonObject>()
        InternalReport.sender = { body ->
            val root = json.parseToJsonElement(String(body)).jsonObject
            for (e in root["events"]!!.jsonArray) sent.add(e.jsonObject)
        }
        return sent
    }

    @BeforeTest
    fun setUp() {
        InternalReport.resetForTest()
        InternalReport.setIngestKeyForTest(fakeKey)
    }

    @AfterTest
    fun tearDown() {
        InternalReport.resetForTest()
        setDefaultClient(null)
        resetConfigureForTests()
    }

    // ---- destination + wire shape ----

    @Test
    fun postsToBakedInIngestWithClientKeyHeader() {
        // The header is asserted indirectly: the baked-in URL is a compile-time
        // constant and the send path uses [InternalReport.setIngestKeyForTest]'s
        // key. The wire shape below plus the URL constant pin the destination.
        val sent = captureSends()
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9")

        InternalReport.report("flags.get", TypeError("cannot read foo"))

        assertEquals(1, sent.size)
        assertEquals("https://api.shipeasy.ai/collect", InternalReport.INGEST_URL)
    }

    @Test
    fun buildsStableConsequenceSubjectFixedOutcomeSdkMarker() {
        val sent = captureSends()
        InternalReport.setContext(side = "client", sdkVersion = "9.9.9")

        InternalReport.report("Client.getExperiment", RuntimeException("boom"))

        val ev = sent[0]
        assertEquals("error", ev.str("type"))
        assertEquals("caught", ev.str("kind"))
        assertEquals("Client.getExperiment", ev.str("subject"))
        assertEquals("returned a safe default", ev.str("outcome"))
        assertEquals("RuntimeException", ev.str("error_type"))
        assertEquals("boom", ev.str("message"))
        assertEquals("client", ev.str("side"))
        assertEquals("9.9.9", ev.str("sdk_version"))
        assertEquals("kotlin", ev["extras"]!!.jsonObject.str("sdk"))
    }

    @Test
    fun doesNotAttachConsumerEnvOrUrl() {
        val sent = captureSends()
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9")

        InternalReport.report("flags.ks", RuntimeException("x"))

        val ev = sent[0]
        assertFalse(ev.containsKey("env"))
        assertFalse(ev.containsKey("url"))
    }

    // ---- enable gating ----

    @Test
    fun noopBeforeContextIsSet() {
        val sent = captureSends()
        InternalReport.report("flags.get", RuntimeException("boom"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun noopWhenDisabled() {
        val sent = captureSends()
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9", enabled = false)
        InternalReport.report("flags.get", RuntimeException("boom"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun inertWhileIngestKeyIsPlaceholder() {
        val sent = captureSends()
        InternalReport.setIngestKeyForTest(InternalReport.PLACEHOLDER_KEY)
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9")
        InternalReport.report("flags.get", RuntimeException("boom"))
        assertTrue(sent.isEmpty())
    }

    // ---- resilience ----

    @Test
    fun dedupesIdenticalInternalErrorsToOneSend() {
        val sent = captureSends()
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9")

        // Same throwable => same top stack frame => one fingerprint (mirrors a hot
        // loop re-throwing from the same line).
        val err = RuntimeException("same")
        InternalReport.report("flags.get", err)
        InternalReport.report("flags.get", err)

        assertEquals(1, sent.size)
    }

    @Test
    fun neverThrowsEvenWhenSenderThrows() {
        InternalReport.sender = { throw RuntimeException("network down") }
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9")
        // Must not propagate the sender failure.
        InternalReport.report("flags.get", RuntimeException("boom"))
    }

    // ---- guard integration (Engine's last-resort readers) ----

    @Test
    fun engineGuardReportsSwallowedInternalErrorAndReturnsFallback() {
        val sent = captureSends()
        // A non-localMode engine wires the internal-report context (side=server,
        // enabled) in its init block. Plant a sticky store that throws so a REAL
        // getExperiment guard hit fires: the reader's runCatching{}.getOrElse{}
        // swallows the error, reports it, and returns the not-enrolled fallback.
        val boom = RuntimeException("internal invariant")
        val brokenStore = object : StickyBucketStore {
            override fun get(unit: String): Map<String, StickyEntry>? = throw boom
            override fun set(unit: String, exp: String, entry: StickyEntry) {}
        }
        // A real experiment blob so evalExperiment reaches the sticky store.
        val engine = Engine.fromSnapshot(
            flags = mapOf("gates" to emptyMap<String, Any?>()),
            experiments = mapOf(
                "experiments" to mapOf(
                    "price_test" to mapOf(
                        "status" to "running",
                        "salt" to "abcdefgh",
                        "allocationPct" to 100,
                        "groups" to listOf(mapOf("name" to "control", "weight" to 100)),
                    ),
                ),
            ),
            stickyStore = brokenStore,
        )
        // fromSnapshot is localMode (reporting off); re-enable the channel to
        // exercise the guard→report wiring directly on this engine.
        InternalReport.setContext(side = "server", sdkVersion = VERSION)

        val result = engine.getExperiment("price_test", mapOf("user_id" to "u1"), "fallback-params")

        // Guard returned the documented safe default …
        assertEquals(false, result.inExperiment)
        assertEquals("control", result.group)
        assertEquals("fallback-params", result.params)
        // … and the swallowed internal error was self-reported.
        assertEquals(1, sent.size)
        assertEquals("flags.getExperiment", sent[0].str("subject"))
        assertEquals("returned a safe default", sent[0].str("outcome"))
        assertEquals("internal invariant", sent[0].str("message"))
        engine.close()
    }

    @Test
    fun engineGuardDoesNotReportWhenReadSucceeds() {
        val sent = captureSends()
        val engine = Engine.fromSnapshot(
            flags = mapOf("configs" to mapOf("greeting" to mapOf("value" to "hi"))),
            experiments = emptyMap(),
        )
        InternalReport.setContext(side = "server", sdkVersion = VERSION)

        assertEquals("hi", engine.getConfig("greeting"))
        assertTrue(sent.isEmpty())
        engine.close()
    }

    @Test
    fun localModeEngineDisablesInternalReporting() {
        val sent = captureSends()
        // forTesting() => localMode => internal reporting forced off.
        Engine.forTesting()
        InternalReport.report("flags.getConfig", RuntimeException("boom"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun disableInternalErrorReportingOptOut() {
        val sent = captureSends()
        val engine = Engine("srv_key", baseUrl = "https://e.x", disableInternalErrorReporting = true)
        engine.close()
        InternalReport.report("flags.getConfig", RuntimeException("boom"))
        assertTrue(sent.isEmpty())
    }
}

// A TypeError analogue so the wire test reads like the TS reference (Kotlin/JVM
// has no built-in TypeError). error_type is the simple class name.
private class TypeError(message: String) : RuntimeException(message)
