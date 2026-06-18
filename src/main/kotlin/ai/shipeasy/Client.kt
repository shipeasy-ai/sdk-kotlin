package ai.shipeasy

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class Client(
    private val apiKey: String,
    baseUrl: String? = null,
    env: String = "prod",
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    // Local (no-network) test mode. Set only via [forTesting]; init/initOnce/
    // track become no-ops and the client never reaches the network. See the
    // "Testing" section of the README.
    private val localMode: Boolean = false,
) : AutoCloseable {
    private val log = Logger.getLogger("shipeasy")
    private val baseUrl: String = (baseUrl ?: "https://edge.shipeasy.dev").trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    // Per-evaluation usage telemetry. ON by default; pass disableTelemetry = true
    // to opt out. Always off in localMode (an empty key disables it too). See
    // Telemetry.kt.
    private val telemetry = Telemetry(
        telemetryUrl ?: "https://t.shipeasy.ai", apiKey, "server", env, disableTelemetry || localMode, http,
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Local overrides (Statsig-style). Thread-safe to match the volatile-blob
    // model: an override, if present, wins over the fetched blob in the getters.
    // configOverrides uses a sentinel so a null override value is honoured.
    private val flagOverrides = ConcurrentHashMap<String, Boolean>()
    private val configOverrides = ConcurrentHashMap<String, NullableBox>()
    private val experimentOverrides = ConcurrentHashMap<String, ExperimentResult>()

    @Volatile private var flagsBlob: Map<String, Any?>? = null
    @Volatile private var expsBlob: Map<String, Any?>? = null
    @Volatile private var flagsEtag: String? = null
    @Volatile private var expsEtag: String? = null
    @Volatile private var pollIntervalSec = 30
    @Volatile private var initialized = false
    private var pollJob: Job? = null

    suspend fun init() {
        if (localMode) return
        fetchAll()
        initialized = true
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalSec * 1000L)
                runCatching { fetchAll() }.onFailure { log.warning("poll failed: ${it.message}") }
            }
        }
    }

    suspend fun initOnce() {
        if (localMode) return
        if (initialized) return
        fetchAll()
        initialized = true
    }

    override fun close() { scope.cancel() }

    /**
     * Seed a local override for a flag. Wins over the fetched blob in [getFlag].
     * Usable on any client; the common case is a [forTesting] client.
     */
    fun overrideFlag(name: String, value: Boolean) { flagOverrides[name] = value }

    /** Seed a local override for a config value (may be null). Wins in [getConfig]. */
    fun overrideConfig(name: String, value: Any?) { configOverrides[name] = NullableBox(value) }

    /**
     * Seed a local override for an experiment. [getExperiment] then returns
     * `ExperimentResult(inExperiment = true, group = group, params = params)`.
     */
    fun overrideExperiment(name: String, group: String, params: Any?) {
        experimentOverrides[name] = ExperimentResult(true, group, params)
    }

    /** Clear every local override (flags, configs, experiments). */
    fun clearOverrides() {
        flagOverrides.clear()
        configOverrides.clear()
        experimentOverrides.clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun getFlag(name: String, user: Map<String, Any?>): Boolean {
        flagOverrides[name]?.let { return it }
        telemetry.emit("gate", name)
        val gates = flagsBlob?.get("gates") as? Map<String, Any?> ?: return false
        return Eval.evalGate(gates[name] as? Map<String, Any?>, withAnonId(user))
    }

    /**
     * Default `anonymous_id` to the request's `__se_anon_id` (resolved by
     * [AnonIdFilter]) when the caller passed no explicit unit. A caller-supplied
     * `user_id`/`anonymous_id` always wins; a no-op when no filter ran.
     */
    private fun withAnonId(user: Map<String, Any?>): Map<String, Any?> {
        val hasUnit = !user["user_id"]?.toString().isNullOrEmpty() ||
            !user["anonymous_id"]?.toString().isNullOrEmpty()
        val anon = AnonId.current()
        return if (!hasUnit && anon != null) user + ("anonymous_id" to anon) else user
    }

    @Suppress("UNCHECKED_CAST")
    fun getConfig(name: String): Any? {
        configOverrides[name]?.let { return it.value }
        telemetry.emit("config", name)
        val configs = flagsBlob?.get("configs") as? Map<String, Any?> ?: return null
        return (configs[name] as? Map<String, Any?>)?.get("value")
    }

    @Suppress("UNCHECKED_CAST")
    fun getExperiment(name: String, user: Map<String, Any?>, defaultParams: Any?): ExperimentResult {
        experimentOverrides[name]?.let { return it }
        telemetry.emit("experiment", name)
        val flags = flagsBlob
        val exps = expsBlob
        val exp = (exps?.get("experiments") as? Map<String, Any?>)?.get(name) as? Map<String, Any?>
        val r = Eval.evalExperiment(exp, flags, exps, withAnonId(user))
        return if (r.params == null) r.copy(params = defaultParams) else r
    }

    fun track(userId: String, eventName: String, properties: Map<String, Any?>? = null) {
        if (localMode) return
        val event = buildMap<String, Any?> {
            put("type", "metric"); put("event_name", eventName); put("user_id", userId)
            put("ts", Instant.now().toEpochMilli())
            if (!properties.isNullOrEmpty()) put("properties", properties)
        }
        val body = mapOf("events" to listOf(event))
        scope.launch {
            runCatching { post("/collect", json.encodeToString(JsonElement.serializer(), toJsonElement(body)).toByteArray()) }
                .onFailure { log.warning("track failed: ${it.message}") }
        }
    }

    private suspend fun fetchAll() = withContext(Dispatchers.IO) {
        val flagsRes = httpGet("/sdk/flags", flagsEtag)
        flagsRes.headers().firstValue("X-Poll-Interval").ifPresent {
            it.toIntOrNull()?.let { v -> pollIntervalSec = v }
        }
        when (flagsRes.statusCode()) {
            200 -> {
                flagsRes.headers().firstValue("ETag").ifPresent { flagsEtag = it }
                flagsBlob = fromJsonElement(json.parseToJsonElement(String(flagsRes.body()))) as? Map<String, Any?>
            }
            304 -> {}
            else -> error("/sdk/flags: ${flagsRes.statusCode()}")
        }
        val expsRes = httpGet("/sdk/experiments", expsEtag)
        when (expsRes.statusCode()) {
            200 -> {
                expsRes.headers().firstValue("ETag").ifPresent { expsEtag = it }
                expsBlob = fromJsonElement(json.parseToJsonElement(String(expsRes.body()))) as? Map<String, Any?>
            }
            304 -> {}
            else -> error("/sdk/experiments: ${expsRes.statusCode()}")
        }
    }

    private fun httpGet(path: String, etag: String?): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-SDK-Key", apiKey)
            .GET()
        if (etag != null) b.header("If-None-Match", etag)
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun post(path: String, body: ByteArray) {
        val req = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-SDK-Key", apiKey)
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.discarding())
        if (res.statusCode() >= 400) error("POST $path: ${res.statusCode()}")
    }

    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, vv) -> k.toString() to toJsonElement(vv) })
        is List<*> -> JsonArray(v.map { toJsonElement(it) })
        else -> JsonPrimitive(v.toString())
    }

    private fun fromJsonElement(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.contentOrNull
            e.booleanOrNull() != null -> e.boolean
            e.longOrNull != null -> e.longOrNull
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.contentOrNull
        }
        is JsonObject -> e.mapValues { fromJsonElement(it.value) }
        is JsonArray -> e.map { fromJsonElement(it) }
    }

    private fun JsonPrimitive.booleanOrNull(): Boolean? =
        when (contentOrNull?.lowercase()) { "true" -> true; "false" -> false; else -> null }

    // Holds a config override value so a null override is distinguishable from
    // "no override" in a ConcurrentHashMap (which cannot store null values).
    private class NullableBox(val value: Any?)

    companion object {
        /**
         * Build a no-network client for tests. Telemetry is disabled, `init()`/
         * `initOnce()`/`track()` are no-ops (never reach the network), and no API
         * key is required. Seed values with [overrideFlag]/[overrideConfig]/
         * [overrideExperiment]; entities with no override fall back to their
         * defaults (flag → false, config → null, experiment → not in experiment).
         */
        @JvmStatic
        fun forTesting(): Client = Client(apiKey = "", disableTelemetry = true, localMode = true)
            .also { it.initialized = true }
    }
}
