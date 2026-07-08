package ai.shipeasy

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Environment-derived network & telemetry (egress) defaults (0.16.0).
 *
 * The suite-wide [InternalReportInertListener] pins `shipeasy.env=production` so
 * every OTHER test's network path stays ON. These tests clear that system
 * property locally (and restore it in teardown) to exercise the dev/prod
 * branching directly.
 */
class EnvDefaultsTest {
    private var savedEnv: String? = null

    @BeforeTest
    fun setup() {
        // Remember and clear the suite-wide prod pin so the native signal is unset
        // and the fallback (configured `env`) drives the decision, unless a test
        // sets the property itself.
        savedEnv = System.getProperty("shipeasy.env")
        System.clearProperty("shipeasy.env")
        setDefaultClient(null)
    }

    @AfterTest
    fun teardown() {
        if (savedEnv != null) System.setProperty("shipeasy.env", savedEnv!!)
        else System.clearProperty("shipeasy.env")
        setDefaultClient(null)
    }

    // ---- isProductionEnv precedence ----

    @Test
    fun systemPropertyWinsAndIsCaseInsensitive() {
        System.setProperty("shipeasy.env", "Production")
        // Even with configuredEnv = "dev", the native signal wins.
        assertTrue(Env.isProductionEnv("dev"))
        System.setProperty("shipeasy.env", "PROD")
        assertTrue(Env.isProductionEnv("dev"))
        System.setProperty("shipeasy.env", "staging")
        assertFalse(Env.isProductionEnv("prod")) // present-but-not-prod ⇒ not prod
    }

    @Test
    fun fallsBackToConfiguredEnvWhenNoNativeSignal() {
        // No system property, no env var set for shipeasy in this JVM.
        assertTrue(Env.isProductionEnv(null))       // defaults to prod
        assertTrue(Env.isProductionEnv("prod"))
        assertTrue(Env.isProductionEnv("PROD"))
        assertFalse(Env.isProductionEnv("dev"))
        assertFalse(Env.isProductionEnv("staging"))
    }

    // ---- Engine egress defaults ----

    // Build a non-localMode engine with a capturing eventSender, run [body], return
    // the captured /collect events. No explicit isNetworkEnabled unless [network]
    // is passed, so the env default decides.
    private fun captureTrack(
        env: String = "prod",
        network: Boolean? = null,
        body: (Engine) -> Unit,
    ): List<Map<String, Any?>> {
        val sent = mutableListOf<Map<String, Any?>>()
        val engine = Engine(
            apiKey = "srv_key",
            baseUrl = "https://e.x",
            env = env,
            isNetworkEnabled = network,
        ).also { it.initialized = true }
        engine.eventSender = { wire -> parseEvents(wire, sent) }
        body(engine)
        return sent
    }

    @Test
    fun offlineByDefaultInDev_noRequestFires() {
        // env = "dev", no native signal, no explicit switch ⇒ offline ⇒ track()
        // is a no-op (nothing reaches the sender).
        val sent = captureTrack(env = "dev") { it.track("u1", "purchase") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun explicitNetworkOnOverridesDevDefault() {
        // env = "dev" but isNetworkEnabled = true forces the network on.
        val sent = captureTrack(env = "dev", network = true) { it.track("u1", "purchase") }
        assertEquals("purchase", sent.single()["event_name"])
    }

    @Test
    fun onByDefaultInProduction() {
        // env = "prod" (fallback), no explicit switch ⇒ network on ⇒ track() fires.
        val sent = captureTrack(env = "prod") { it.track("u1", "purchase") }
        assertEquals("purchase", sent.single()["event_name"])
    }

    @Test
    fun explicitNetworkOffOverridesProdDefault() {
        val sent = captureTrack(env = "prod", network = false) { it.track("u1", "purchase") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun systemPropertyProdRestoresNetworkEvenWithDevConfiguredEnv() {
        // A native prod signal outranks the configured env option.
        System.setProperty("shipeasy.env", "production")
        val sent = captureTrack(env = "dev") { it.track("u1", "purchase") }
        assertEquals("purchase", sent.single()["event_name"])
    }

    @Test
    fun seeIsNoOpWhenOfflineByDefaultInDev() {
        // The see() error-report egress is gated by the same offline switch.
        val sent = mutableListOf<String>()
        val engine = Engine(apiKey = "srv_key", baseUrl = "https://e.x", env = "dev")
        engine.seeSender = { sent.add(String(it)) }
        engine.seeViolation("checkout_failed").causesThe("checkout").to("use cached prices")
        assertTrue(sent.isEmpty())
    }

    // ---- ShipeasyClient (native mobile) egress defaults ----

    @Test
    fun nativeClientOfflineInDev_noEvaluateOrTrack() {
        val calls = mutableListOf<String>()
        val transport: ClientTransport = { path, _ -> calls.add(path); 200 to "{}".toByteArray() }
        val client = ShipeasyClient(clientKey = "pk", env = "dev", transport = transport)
        kotlinx.coroutines.runBlocking { client.identify(mapOf("user_id" to "u1")) }
        client.track("purchase")
        assertTrue(calls.isEmpty(), "offline dev client must make no network call")
        // Reads fall back to defaults (never evaluated).
        assertFalse(client.getFlag("anything"))
    }

    @Test
    fun nativeClientNetworkOnInDevWhenForced() {
        val calls = mutableListOf<String>()
        val transport: ClientTransport = { path, _ -> calls.add(path); 200 to "{\"flags\":{}}".toByteArray() }
        val client = ShipeasyClient(
            clientKey = "pk", env = "dev", isNetworkEnabled = true, transport = transport,
        )
        kotlinx.coroutines.runBlocking { client.identify(mapOf("user_id" to "u1")) }
        assertTrue(calls.any { it.startsWith("/sdk/evaluate") }, "forced-on dev client must evaluate")
    }

    // ---- helpers ----

    @Suppress("UNCHECKED_CAST")
    private fun parseEvents(wire: ByteArray, into: MutableList<Map<String, Any?>>) {
        val root = parseJson(String(wire)) as Map<String, Any?>
        for (e in root["events"] as List<*>) into.add(e as Map<String, Any?>)
    }

    private fun parseJson(s: String): Any? =
        jsonToAny(kotlinx.serialization.json.Json.parseToJsonElement(s))

    private fun jsonToAny(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonObject -> e.mapValues { jsonToAny(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.map { jsonToAny(it) }
        is kotlinx.serialization.json.JsonPrimitive ->
            if (e.isString) e.content
            else e.content.toBooleanStrictOrNull() ?: e.content.toDoubleOrNull() ?: e.content
    }
}
