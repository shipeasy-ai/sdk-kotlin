package ai.shipeasy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Native mobile client (`ShipeasyClient`): the client-key `/sdk/evaluate` path
 * plus the whole point of the client SDK — a device `anonymous_id` PERSISTED
 * across launches so a logged-out visitor buckets identically every cold start.
 * Hermetic: an in-memory [AnonStore] + an injected transport (no network).
 */
class ClientModeTest {

    /** Records every request; replies to `/sdk/evaluate` with a canned body. */
    private class StubTransport(var evaluateJson: String = "{}") {
        val requests: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        var failEvaluate = false

        val fn: ClientTransport = { path, body ->
            requests.add(path to String(body, StandardCharsets.UTF_8))
            when {
                path.startsWith("/sdk/evaluate") && failEvaluate -> throw java.io.IOException("offline")
                path.startsWith("/sdk/evaluate") -> 200 to evaluateJson.toByteArray()
                else -> 200 to ByteArray(0)
            }
        }

        fun requestsFor(prefix: String): List<Pair<String, String>> =
            synchronized(requests) { requests.filter { it.first.startsWith(prefix) } }
    }

    private fun client(store: AnonStore, stub: StubTransport, privateAttributes: List<String> = emptyList()) =
        ShipeasyClient(clientKey = "pk", store = store, disableTelemetry = true, privateAttributes = privateAttributes, transport = stub.fn)

    private fun anonIdFromEvaluate(stub: StubTransport): String? {
        val body = stub.requestsFor("/sdk/evaluate").firstOrNull()?.second ?: return null
        val user = Json.parseToJsonElement(body).jsonObject["user"]?.jsonObject ?: return null
        return user["anonymous_id"]?.jsonPrimitive?.content
    }

    @Test
    fun adoptsPersistedAnonId() = runBlocking {
        val store = InMemoryAnonStore(mapOf("__se_anon_id" to "anon_persisted_123"))
        val stub = StubTransport("""{"flags":{},"configs":{},"experiments":{},"killswitches":{}}""")
        val c = client(store, stub)
        assertEquals("anon_persisted_123", c.anonymousId)
        c.identify(mapOf("user_id" to "u1"))
        assertEquals("anon_persisted_123", anonIdFromEvaluate(stub))
    }

    @Test
    fun mintsAndPersistsOnFirstRun() {
        val store = InMemoryAnonStore()
        val stub = StubTransport("""{"flags":{},"configs":{},"experiments":{},"killswitches":{}}""")
        val c = client(store, stub)
        assertTrue(c.anonymousId.isNotEmpty())
        // Written back for the next launch.
        assertEquals(c.anonymousId, store.get("__se_anon_id"))
    }

    @Test
    fun readsReturnDefaultsBeforeIdentify() {
        val stub = StubTransport()
        val c = client(InMemoryAnonStore(), stub)
        assertFalse(c.getFlag("f"))
        assertNull(c.getConfig("cfg"))
        assertFalse(c.universe("checkout").assign().enrolled)
        assertFalse(c.getKillswitch("k"))
    }

    @Test
    fun identifyEvaluatesAndCaches() = runBlocking {
        val stub = StubTransport(
            """{"flags":{"new_ui":true},"configs":{"theme":{"accent":"blue"}},""" +
                """"experiments":{"exp1":{"inExperiment":true,"group":"treatment","params":{"copy":"hi"},"universe":"checkout"}},""" +
                """"universes":{"checkout":{"defaults":{"copy":"default"}}},""" +
                """"killswitches":{"payments":true}}""",
        )
        val c = client(InMemoryAnonStore(), stub)
        c.identify(mapOf("user_id" to "u1"))

        assertTrue(c.getFlag("new_ui"))
        @Suppress("UNCHECKED_CAST")
        val theme = c.getConfig("theme") as? Map<String, Any?>
        assertEquals("blue", theme?.get("accent"))
        val a = c.universe("checkout").assign()
        assertTrue(a.enrolled)
        assertEquals("treatment", a.group)
        assertEquals("exp1", a.name)
        // Edge pre-merges universe defaults into params; the variant value wins.
        assertEquals("hi", a.get("copy"))
        assertTrue(c.getKillswitch("payments"))
    }

    @Test
    fun failedEvaluateIsNonFatal() = runBlocking {
        val stub = StubTransport()
        stub.failEvaluate = true
        val c = client(InMemoryAnonStore(), stub)
        c.identify(mapOf("user_id" to "u1")) // must not throw
        assertTrue(c.getFlag("f", default = true), "reads fall back to the default when no assignments loaded")
    }

    @Test
    fun trackPostsToCollectWithAnonId() = runBlocking {
        val store = InMemoryAnonStore(mapOf("__se_anon_id" to "anon_x"))
        val stub = StubTransport("""{"flags":{},"configs":{},"experiments":{},"killswitches":{}}""")
        val c = client(store, stub)
        c.identify(mapOf("user_id" to "u1"))
        c.track("checkout", mapOf("value" to 42))

        // Give the fire-and-forget /collect coroutine a moment.
        var collects = emptyList<Pair<String, String>>()
        repeat(20) {
            collects = stub.requestsFor("/collect")
            if (collects.any { it.second.contains("\"metric\"") }) return@repeat
            Thread.sleep(20)
        }
        val metric = collects.map { it.second }.firstOrNull { it.contains("\"metric\"") }
        assertNotNull(metric)
        assertTrue(metric.contains("\"anonymous_id\":\"anon_x\""))
        assertTrue(metric.contains("\"event_name\":\"checkout\""))
    }

    @Test
    fun stickyStateRoundTripsAndPersists() = runBlocking {
        val store = InMemoryAnonStore()
        val stub = StubTransport(
            """{"flags":{},"configs":{},"experiments":{},"killswitches":{},""" +
                """"sticky":{"exp1":{"g":"treatment","s":"abc12345"}}}""",
        )
        val c = client(store, stub)
        c.identify(mapOf("user_id" to "u1"))
        // Persisted for the next launch.
        assertNotNull(store.get("__se_sticky"))
        // A second evaluate echoes the sticky state back to the edge.
        c.refreshAssignments()
        val lastBody = stub.requestsFor("/sdk/evaluate").last().second
        assertTrue(lastBody.contains("\"sticky\""))
        assertTrue(lastBody.contains("exp1"))
    }
}
