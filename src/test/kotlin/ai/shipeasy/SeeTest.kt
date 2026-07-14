package ai.shipeasy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @AfterTest
    fun reset() {
        // Each Engine constructor registers itself as the default; clear it so a
        // "no client yet" test isn't polluted by a sibling test's leftover.
        setDefaultClient(null)
    }

    // Build a real client (not localMode, so dispatchSee runs) but capture the
    // /collect body synchronously via the injectable seeSender seam — exactly the
    // pattern TelemetryTest uses for its injectable sender.
    private fun captureClient(
        privateAttributes: List<String> = emptyList(),
    ): Pair<Engine, MutableList<JsonObject>> {
        val sent = mutableListOf<JsonObject>()
        val c = Engine("srv_key", baseUrl = "https://e.x", privateAttributes = privateAttributes)
        c.seeSender = { body ->
            val root = json.parseToJsonElement(String(body)).jsonObject
            for (e in root["events"]!!.jsonArray) sent.add(e.jsonObject)
        }
        return c to sent
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun caughtExceptionReportsErrorEvent() {
        val (c, sent) = captureClient()
        try {
            throw IllegalArgumentException("boom")
        } catch (e: IllegalArgumentException) {
            c.see(e).causesThe("checkout").to("use cached prices")
        }
        val ev = sent[0]
        assertEquals("error", ev.str("type"))
        assertEquals("caught", ev.str("kind"))
        assertEquals("IllegalArgumentException", ev.str("error_type"))
        assertEquals("boom", ev.str("message"))
        assertEquals("checkout", ev.str("subject"))
        assertEquals("use cached prices", ev.str("outcome"))
        assertEquals("server", ev.str("side"))
        assertEquals(VERSION, ev.str("sdk_version"))
        assertEquals("prod", ev.str("env"))
        assertTrue(ev.containsKey("stack"))
        assertTrue(ev.containsKey("ts"))
    }

    @Test
    fun extrasBeforeToAreSanitizedAndSent() {
        val (c, sent) = captureClient()
        c.see(RuntimeException("x")).causesThe("photo upload").extras(
            mapOf("photo_id" to "p1", "size" to 42, "ok" to true, "skip" to null),
        ).to("be rejected")
        val extras = sent[0]["extras"]!!.jsonObject
        assertEquals("p1", extras.str("photo_id"))
        assertEquals(42, extras["size"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, extras["ok"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(extras.containsKey("skip"))
        assertEquals(3, extras.size)
    }

    @Test
    fun inlineExtrasOnToMergeOverPriorExtras() {
        val (c, sent) = captureClient()
        // .extras before .to seeds keys; the inline .to(outcome, map) form folds
        // on top (later wins on a repeated key), so ordering is irrelevant.
        c.see(RuntimeException("x")).causesThe("checkout")
            .extras(mapOf("order_id" to "o1", "attempt" to 1))
            .to("use cached prices", mapOf("attempt" to 2, "region" to "eu"))
        val extras = sent[0]["extras"]!!.jsonObject
        assertEquals("o1", extras.str("order_id"))              // from .extras
        assertEquals(2, extras["attempt"]!!.jsonPrimitive.content.toInt()) // inline .to wins
        assertEquals("eu", extras.str("region"))               // added by inline .to
        assertEquals("use cached prices", sent[0].str("outcome"))
    }

    @Test
    fun violationUsesViolationKind() {
        val (c, sent) = captureClient()
        c.seeViolation("large query").causesThe("search results").to("be trimmed")
        val ev = sent[0]
        assertEquals("violation", ev.str("kind"))
        assertEquals("large query", ev.str("error_type"))
        assertEquals("large query", ev.str("message"))
        assertEquals("search results", ev.str("subject"))
        assertFalse(ev.containsKey("stack"))
    }

    @Test
    fun controlFlowMarksAndReportsNothing() {
        val (c, sent) = captureClient()
        val e = IllegalStateException("not a Foo")
        c.controlFlowException(e).because("because it wasn't an encoded Foo")
            .extras(mapOf("tried" to "Foo"))
        assertTrue(isExpected(e))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun toIsRequiredNoSendWithoutTerminal() {
        val (c, sent) = captureClient()
        c.see(RuntimeException("x")).causesThe("checkout") // no to()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun toIsIdempotent() {
        val (c, sent) = captureClient()
        val chain = c.see(RuntimeException("x")).causesThe("checkout")
        chain.to("a")
        chain.to("b")
        assertEquals(1, sent.size)
    }

    @Test
    fun defaultsWhenConsequenceOmitted() {
        val (c, sent) = captureClient()
        c.see(RuntimeException("x")).to("be incomplete")
        assertEquals("app", sent[0].str("subject"))
    }

    @Test
    fun nonThrowableProblemUsesErrorType() {
        val (c, sent) = captureClient()
        c.see("plain string problem").causesThe("import").to("be skipped")
        val ev = sent[0]
        assertEquals("caught", ev.str("kind"))
        assertEquals("Error", ev.str("error_type"))
        assertEquals("plain string problem", ev.str("message"))
        assertFalse(ev.containsKey("stack"))
    }

    @Test
    fun testModeIsNoop() {
        val sent = mutableListOf<ByteArray>()
        val c = Engine.forTesting()
        c.seeSender = { sent.add(it) }
        c.see(RuntimeException("x")).causesThe("checkout").to("use cached prices")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun globalSeeUsesLastConstructedClient() {
        val sent = mutableListOf<JsonObject>()
        val c = Engine("srv_key", baseUrl = "https://e.x")
        c.seeSender = { body ->
            for (e in json.parseToJsonElement(String(body)).jsonObject["events"]!!.jsonArray) {
                sent.add(e.jsonObject)
            }
        }
        // The package-level see() routes to the last-constructed client (c).
        see(RuntimeException("global")).causesThe("dashboard").to("show cached data")
        assertEquals("dashboard", sent[0].str("subject"))
    }

    @Test
    fun globalSeeBeforeClientWarnsAndDrops() {
        setDefaultClient(null)
        // No client → no crash, nothing sent, returns a no-op chain.
        see(RuntimeException("x")).causesThe("checkout").to("use cached prices")
        seeViolation("v").to("be dropped")
    }

    @Test
    fun sanitizeExtrasCapsKeysAndValueLength() {
        val big = LinkedHashMap<String, Any?>()
        for (i in 0 until 30) big["k$i"] = i
        big["long"] = "x".repeat(500)
        val out = sanitizeExtras(big)!!
        assertTrue(out.size <= 20)
    }

    @Test
    fun sanitizeExtrasTruncatesAndDropsNonFinite() {
        val out = sanitizeExtras(
            mapOf(
                "s" to "y".repeat(500),
                "nan" to Double.NaN,
                "inf" to Double.POSITIVE_INFINITY,
                "n" to 7,
                "nullv" to null,
            ),
        )!!
        assertEquals(200, (out["s"] as String).length)
        assertFalse(out.containsKey("nan"))
        assertFalse(out.containsKey("inf"))
        assertFalse(out.containsKey("nullv"))
        assertEquals(7, out["n"])
    }

    @Test
    fun emptyExtrasReturnNull() {
        assertNull(sanitizeExtras(emptyMap()))
        assertNull(sanitizeExtras(null))
        assertNull(sanitizeExtras(mapOf("only" to null)))
    }

    @Test
    fun privateAttributesStrippedFromExtras() {
        val (c, sent) = captureClient(privateAttributes = listOf("secret"))
        c.see(RuntimeException("x")).causesThe("checkout").extras(
            mapOf("secret" to "shh", "ok" to "yes"),
        ).to("use cached prices")
        val extras = sent[0]["extras"]!!.jsonObject
        assertFalse(extras.containsKey("secret"))
        assertEquals("yes", extras.str("ok"))
    }
}
