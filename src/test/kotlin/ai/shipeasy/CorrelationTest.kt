package ai.shipeasy

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

// shipeasy #704: the browser SDK sends a per-request token on X-SE-Correlation;
// AnonIdFilter parks it in SeeContext so every see() in the request carries it
// as `correlation_id`, which ingest joins on to link client and server errors.
class CorrelationTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val token = "0b7c1d2e-3f40-4a5b-8c6d-7e8f9a0b1c2d"

    @AfterTest
    fun reset() {
        SeeContext.clear()
        AnonId.clear()
        setDefaultClient(null)
    }

    // Minimal reflective stand-ins — no mocking library on the test classpath.
    private fun request(headers: Map<String, String>): HttpServletRequest =
        Proxy.newProxyInstance(
            HttpServletRequest::class.java.classLoader,
            arrayOf(HttpServletRequest::class.java),
        ) { _, m, args ->
            when (m.name) {
                "getHeader" -> headers.entries.firstOrNull { it.key.equals(args!![0] as String, true) }?.value
                "getCookies" -> null
                "isSecure" -> false
                else -> null
            }
        } as HttpServletRequest

    private fun response(): HttpServletResponse =
        Proxy.newProxyInstance(
            HttpServletResponse::class.java.classLoader,
            arrayOf(HttpServletResponse::class.java),
        ) { _, _, _ -> null } as HttpServletResponse

    private fun captureClient(): Pair<Engine, MutableList<JsonObject>> {
        val sent = mutableListOf<JsonObject>()
        val c = Engine("srv_key", baseUrl = "https://e.x")
        c.seeSender = { body ->
            val root = json.parseToJsonElement(String(body)).jsonObject
            for (e in root["events"]!!.jsonArray) sent.add(e.jsonObject)
        }
        return c to sent
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun filterSetsCorrelationDuringRequestAndClearsAfter() {
        var seen: String? = null
        AnonIdFilter().doFilter(
            request(mapOf("X-SE-Correlation" to token)),
            response(),
            FilterChain { _, _ -> seen = SeeContext.correlationId() },
        )
        assertEquals(token, seen)
        assertNull(SeeContext.correlationId())
    }

    @Test
    fun filterClearsCorrelationWhenChainThrows() {
        assertFailsWith<IllegalStateException> {
            AnonIdFilter().doFilter(
                request(mapOf("X-SE-Correlation" to token)),
                response(),
                FilterChain { _, _ -> error("handler blew up") },
            )
        }
        assertNull(SeeContext.correlationId())
        assertNull(AnonId.current())
    }

    @Test
    fun filterDropsMalformedToken() {
        var seen: String? = "unset"
        AnonIdFilter().doFilter(
            request(mapOf("X-SE-Correlation" to "bad token!")),
            response(),
            FilterChain { _, _ -> seen = SeeContext.correlationId() },
        )
        assertNull(seen)
    }

    @Test
    fun seeInsideFilteredRequestCarriesCorrelationId() {
        val (c, sent) = captureClient()
        AnonIdFilter().doFilter(
            request(mapOf("X-SE-Correlation" to token)),
            response(),
            FilterChain { _, _ -> c.see(IllegalStateException("in request")).causesThe("checkout").to("fail") },
        )
        assertEquals(token, sent.single().str("correlation_id"))
    }

    @Test
    fun eventOmitsCorrelationIdWhenAbsent() {
        val (c, sent) = captureClient()
        c.see(IllegalStateException("no request")).causesThe("checkout").to("fail")
        assertFalse(sent.single().containsKey("correlation_id"))
    }

    @Test
    fun buildSeeEventValidatesCorrelation() {
        fun build(corr: String?) = buildSeeEvent(
            RuntimeException("x"), "s", "o", null, side = "server", sdkVersion = VERSION, env = null,
            correlationId = corr,
        )
        assertEquals(token, build(token)["correlation_id"])
        assertEquals("abcdEFGH", build("  abcdEFGH ")["correlation_id"])
        for (bad in listOf(null, "", "short", "has space in it", "x".repeat(65), "under_score1", "a;b<c>d=e")) {
            assertFalse(build(bad).containsKey("correlation_id"), "expected '$bad' to be dropped")
        }
    }

    @Test
    fun setCorrelationIdWithInvalidValueClearsIt() {
        SeeContext.setCorrelationId(token)
        assertEquals(token, SeeContext.correlationId())
        SeeContext.setCorrelationId("nope")
        assertNull(SeeContext.correlationId())
    }
}
