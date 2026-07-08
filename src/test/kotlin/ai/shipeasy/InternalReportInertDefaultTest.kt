package ai.shipeasy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the CI safety net installed by [InternalReportInertListener]: during the
 * SDK's OWN test run the baked-in production ingest key must be replaced by the
 * inert placeholder so no test can fire a real
 * `POST https://api.shipeasy.ai/collect`.
 *
 * Unlike [InternalReportTest] (which sets its own fake key per test), this class
 * NEVER installs a key, so it observes the DEFAULT state the suite-wide listener
 * leaves behind. If the listener regresses (or the real key leaks into the run),
 * [buildRealEngineAndTripGuardSendsNothing] would try a real send and this test
 * would flip.
 */
class InternalReportInertDefaultTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @AfterTest
    fun tearDown() {
        InternalReport.resetForTest()
        setDefaultClient(null)
        resetConfigureForTests()
    }

    @Test
    fun defaultIngestKeyDuringTestsIsInertPlaceholder() {
        // The listener ran at session open and pinned the placeholder. We do NOT
        // touch the key here, so the send path is gated off by keyConfigured().
        val sent = mutableListOf<Any>()
        InternalReport.sender = { body ->
            for (e in json.parseToJsonElement(String(body)).jsonObject["events"]!!.jsonArray) sent.add(e)
        }
        // Enable the channel exactly as a live (non-localMode) engine would, then
        // trip a guard-style report. Because the ingest key is the placeholder,
        // report() short-circuits before ever calling the sender.
        InternalReport.setContext(side = "server", sdkVersion = "9.9.9")
        InternalReport.report("flags.get", RuntimeException("boom"))

        assertTrue(sent.isEmpty(), "internal report must be inert while the ingest key is the placeholder")
    }

    @Test
    fun buildRealEngineAndTripGuardSendsNothing() {
        // A REAL (non-localMode) engine wires the internal-report context enabled.
        // Its guard would send on a swallowed internal error — but the suite-wide
        // placeholder gate keeps it inert. Recorder proves zero sends.
        val sent = mutableListOf<Any>()
        InternalReport.sender = { body ->
            for (e in json.parseToJsonElement(String(body)).jsonObject["events"]!!.jsonArray) sent.add(e)
        }

        val boom = RuntimeException("internal invariant")
        val brokenStore = object : StickyBucketStore {
            override fun get(unit: String): Map<String, StickyEntry>? = throw boom
            override fun set(unit: String, exp: String, entry: StickyEntry) {}
        }
        // fromSnapshot is localMode (reporting off in its ctor); re-enable the
        // channel to simulate a live engine's context and drive the guard.
        val engine = Engine.fromSnapshot(
            flags = mapOf("gates" to emptyMap<String, Any?>()),
            experiments = mapOf(
                "universes" to mapOf("u" to mapOf("holdout_range" to null)),
                "experiments" to mapOf(
                    "price_test" to mapOf(
                        "universe" to "u",
                        "status" to "running",
                        "salt" to "abcdefgh",
                        "allocationPct" to 100,
                        "groups" to listOf(mapOf("name" to "control", "weight" to 100)),
                    ),
                ),
            ),
            stickyStore = brokenStore,
        )
        InternalReport.setContext(side = "server", sdkVersion = VERSION)

        val result = engine.assignUniverse("u", mapOf("user_id" to "u1"))

        // Guard still returns the safe (not-enrolled) default …
        assertFalse(result.enrolled)
        // … but nothing left the process, because the key is the inert placeholder.
        assertTrue(sent.isEmpty(), "real engine guard must not send while the ingest key is the placeholder")
        engine.close()
    }
}
