package ai.shipeasy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Native mobile client for Android (and any JVM client app).
 *
 * The [configure] / [Client] front door is the SERVER SDK: it holds a **server**
 * key, pulls the raw rules blobs (`GET /sdk/flags` + `/sdk/experiments`) and
 * evaluates locally. That is wrong for a shipped app — a server key must never be
 * embedded in an app binary, and the edge forbids client keys from those blob
 * routes (they are server-key only).
 *
 * [ShipeasyClient] is the CLIENT SDK for that case. It holds a **public client
 * key** (safe to ship), evaluates a single device user server-side over
 * `POST /sdk/evaluate`, and caches the returned assignments. Reads are cheap
 * local lookups. Crucially it persists the device's `anonymous_id` (via
 * [AnonStore]) so a logged-out visitor buckets **identically across app
 * launches** — without persistence a fresh UUID every cold start silently
 * re-buckets every fractional rollout and experiment.
 *
 * On Android, back [AnonStore] with SharedPreferences via the
 * `ai.shipeasy:shipeasy-kotlin-android` artifact (`SharedPreferencesAnonStore` /
 * `configureAndroid(context, clientKey)`), which resolves the persistence for
 * you.
 *
 * ```kotlin
 * configureClient(clientKey = "pk_live_…", store = myAnonStore) // once, at launch
 * shipeasyClient()?.identify(mapOf("user_id" to "u_123"))       // suspend: evaluate + cache
 * val on = shipeasyClient()?.getFlag("new_checkout") ?: false   // cheap cache read
 * ```
 */

/**
 * Persistent, platform-scoped storage for the device's stable anonymous
 * bucketing id (the cross-SDK `__se_anon_id`). Must survive app launches, or a
 * logged-out user re-buckets every fractional rollout on every cold start.
 * `get`/`set` are synchronous and best-effort — a slow or throwing backing store
 * must degrade gracefully, never crash a read.
 *
 * Android apps use `SharedPreferencesAnonStore` from the
 * `ai.shipeasy:shipeasy-kotlin-android` artifact; provide your own to back the id
 * with EncryptedSharedPreferences, DataStore, or a test map.
 */
interface AnonStore {
    /** The stored value for [key], or null when absent/unreadable. */
    fun get(key: String): String?

    /** Persist [value] under [key]. Best-effort — failures must be swallowed. */
    fun set(key: String, value: String)
}

/** In-memory [AnonStore] for tests and no-persistence fallbacks. */
class InMemoryAnonStore(seed: Map<String, String> = emptyMap()) : AnonStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>(seed)
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) { map[key] = value }
}

/**
 * Transport seam: `(path, requestBody) -> (statusCode, responseBody)`. Defaults
 * to a real `java.net.http` POST; tests inject a stub so the suite is hermetic.
 */
typealias ClientTransport = (path: String, body: ByteArray) -> Pair<Int, ByteArray>

/**
 * The native mobile client handle. Holds a public client key, one device user,
 * and the cached assignments from the last `POST /sdk/evaluate`. Build once per
 * app via [configureClient]; read it back with [shipeasyClient]. Reads are
 * blocking cache lookups (safe on any thread); [identify] is `suspend` (it does
 * the network round-trip on [Dispatchers.IO]).
 */
class ShipeasyClient internal constructor(
    private val clientKey: String,
    baseUrl: String? = null,
    private val env: String = "prod",
    private val store: AnonStore = InMemoryAnonStore(),
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    // Master network switch. null ⇒ environment-derived (ON in production, OFF in
    // dev/CI/test — see [Env.isProductionEnv]): the client stays fully offline
    // (reads served from cache/defaults, nothing sent) outside production unless
    // you opt in. Pass `true`/`false` to force it.
    isNetworkEnabled: Boolean? = null,
    // Usage-telemetry switch. null ⇒ environment-derived (same inference). Forced
    // off whenever the network is off; `disableTelemetry = true` still forces off.
    isTrackingEnabled: Boolean? = null,
    private val privateAttributes: List<String> = emptyList(),
    private val transport: ClientTransport? = null,
) {
    private val baseUrl: String = (baseUrl ?: "https://api.shipeasy.ai").trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Master network gate — resolved once. Honour an explicit isNetworkEnabled,
    // else default to prod-on (see [Env.isProductionEnv]). When offline the client
    // makes no network call: identify()/refresh()/track() are no-ops and reads are
    // served from cache / defaults.
    private val networkEnabled: Boolean = isNetworkEnabled ?: Env.isProductionEnv(env)
    private val offline: Boolean = !networkEnabled

    // Whether the per-eval usage beacon may fire. Off whenever the network is off;
    // else honour an explicit isTrackingEnabled (or the legacy disableTelemetry),
    // else default to prod-on (quiet outside production).
    private val trackingEnabled: Boolean =
        networkEnabled && !disableTelemetry && (isTrackingEnabled ?: Env.isProductionEnv(env))

    private val telemetry = Telemetry(
        endpoint = telemetryUrl ?: "https://t.shipeasy.ai",
        sdkKey = clientKey,
        side = "client",
        env = env,
        disabled = !trackingEnabled,
        http = http,
    )

    /** The stable, persisted anonymous bucketing id — resolved once at construction. */
    val anonymousId: String

    @Volatile private var userId: String? = null
    @Volatile private var userAttributes: Map<String, Any?> = emptyMap()

    @Volatile private var flags: Map<String, Boolean> = emptyMap()
    @Volatile private var configs: Map<String, Any?> = emptyMap()
    @Volatile private var experiments: Map<String, CachedExp> = emptyMap()
    // Per-universe param defaults (name → default map) from the edge, so
    // `universe(name).assign().get()` resolves to the universe default even when
    // the unit is not enrolled anywhere in the universe.
    @Volatile private var universeDefaults: Map<String, Map<String, Any?>> = emptyMap()
    @Volatile private var killswitches: Map<String, Any?> = emptyMap()
    @Volatile private var sticky: Map<String, Any?> = emptyMap()
    @Volatile private var ready = false

    // Bounded per-session exposure dedup (`uid:exp`) so repeated assign() calls
    // don't double-log an exposure. Mirrors the browser EventBuffer dedup.
    private val exposureSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // Resolve the stable device anon id: adopt the persisted one if valid,
        // else mint a fresh UUID and write it back for the next launch. This is
        // the whole point of the client SDK — bucketing stability across launches.
        val existing = store.get(ANON_KEY)
        anonymousId = if (isValidAnon(existing)) {
            existing!!
        } else {
            val minted = UUID.randomUUID().toString()
            runCatching { store.set(ANON_KEY, minted) }
            minted
        }
        // Restore persisted sticky state so a cold start keeps enrolled units pinned.
        runCatching {
            store.get(STICKY_KEY)?.let { raw ->
                @Suppress("UNCHECKED_CAST")
                sticky = fromJsonElement(json.parseToJsonElement(raw)) as? Map<String, Any?> ?: emptyMap()
            }
        }
    }

    // ---- Identity ----

    /**
     * Bind the current device user and refresh assignments over the network.
     * The persisted [anonymousId] is always attached, so a signed-in user stays
     * tied to the same device identity. Call at launch (with an empty map for a
     * logged-out visitor), on login, and whenever targeting attributes change.
     */
    suspend fun identify(user: Map<String, Any?> = emptyMap()) {
        userAttributes = user
        user["user_id"]?.toString()?.takeIf { it.isNotEmpty() }?.let { userId = it }
        refresh()
        userId?.let { uid ->
            send(mapOf("type" to "identify", "user_id" to uid, "anonymous_id" to anonymousId, "ts" to now()))
        }
    }

    /** Clear the signed-in user (logout) but keep the stable device id, then re-evaluate anonymously. */
    suspend fun reset() {
        userId = null
        userAttributes = emptyMap()
        refresh()
    }

    /** Re-evaluate for the currently-bound user without changing identity. */
    suspend fun refreshAssignments() = refresh()

    // ---- Reads (served from the cached evaluate response) ----

    /** The bound gate value, or [default] when not yet evaluated or absent. Never throws. */
    @JvmOverloads
    fun getFlag(name: String, default: Boolean = false): Boolean = runCatching {
        telemetry.emit("gate", name)
        if (!ready) default else flags[name] ?: default
    }.getOrElse { Log.error("ShipeasyClient.getFlag('$name') threw: ${it.message}"); default }

    /** The bound dynamic config value, or [default] when absent. Never throws. */
    @JvmOverloads
    fun getConfig(name: String, default: Any? = null): Any? = runCatching {
        telemetry.emit("config", name)
        if (!ready || !configs.containsKey(name)) default else configs[name]
    }.getOrElse { Log.error("ShipeasyClient.getConfig('$name') threw: ${it.message}"); default }

    /**
     * Assign the device user within a universe: `universe("checkout").assign()`. A
     * universe is a mutual-exclusion pool, so the unit lands in ≤1 experiment; the
     * returned [Assignment] exposes `.group` / `.get(field, fallback)` and
     * auto-logs one exposure when enrolled (pass `logExposure = false` to suppress).
     * An un-enrolled unit still resolves `get()` to the universe defaults. This is
     * the sole experiment read path — there is no `getExperiment`. Never throws.
     */
    fun universe(name: String): ClientUniverseHandle = ClientUniverseHandle(name)

    /** Reusable per-universe handle reading the cached `/sdk/evaluate` response. */
    inner class ClientUniverseHandle internal constructor(private val universeName: String) {
        @JvmOverloads
        fun assign(logExposure: Boolean = true): Assignment = runCatching {
            telemetry.emit("experiment", universeName)
            val defaults = universeDefaults[universeName] ?: emptyMap()
            if (!ready) return@runCatching Assignment(null, null, defaults)
            for ((expName, entry) in experiments) {
                if (entry.universe != universeName || !entry.inExperiment) continue
                if (logExposure) emitExposure(expName, entry.group)
                // Params are already merged (universe defaults ⊕ variant) by the edge.
                return@runCatching Assignment(expName, entry.group, entry.params ?: defaults)
            }
            Assignment(null, null, defaults)
        }.getOrElse {
            Log.error("ShipeasyClient.universe('$universeName').assign() threw: ${it.message}")
            Assignment(null, null, emptyMap())
        }
    }

    /** Whether killswitch [name] (optionally a named per-key override) is engaged. Never throws. */
    @JvmOverloads
    fun getKillswitch(name: String, switchKey: String? = null): Boolean = runCatching {
        if (!ready) return@runCatching false
        val entry = killswitches[name] ?: return@runCatching false
        if (switchKey != null && entry is Map<*, *>) {
            entry[switchKey]?.let { return@runCatching truthy(it) }
        }
        if (entry is Map<*, *>) truthy(entry["value"] ?: entry["enabled"]) else truthy(entry)
    }.getOrElse { Log.error("ShipeasyClient.getKillswitch('$name') threw: ${it.message}"); false }

    // ---- Events ----

    /** Record a conversion event for the bound user. Private attrs stripped. Fire-and-forget. */
    @JvmOverloads
    fun track(event: String, props: Map<String, Any?> = emptyMap()) {
        runCatching {
            val ev = buildMap<String, Any?> {
                put("type", "metric")
                put("event_name", event)
                userId?.let { put("user_id", it) }
                put("anonymous_id", anonymousId)
                put("ts", now())
                val safe = stripPrivate(props)
                if (safe.isNotEmpty()) put("properties", safe)
            }
            send(ev)
        }.onFailure { Log.error("ShipeasyClient.track('$event') failed: ${it.message}") }
    }

    /**
     * Emit a single deduped exposure for an enrolled `(experiment, group)`.
     * Auto-called by `universe(name).assign()` when the unit is enrolled (unless
     * `assign(logExposure = false)`). Fire-and-forget; deduped per session so a
     * repeated assign never double-counts.
     */
    private fun emitExposure(experiment: String, group: String) {
        runCatching {
            val dedupKey = "${userId ?: anonymousId}:$experiment"
            if (!exposureSeen.add(dedupKey)) return
            val ev = buildMap<String, Any?> {
                put("type", "exposure")
                put("experiment", experiment)
                put("group", group)
                userId?.let { put("user_id", it) }
                put("anonymous_id", anonymousId)
                put("ts", now())
            }
            send(ev)
        }.onFailure { Log.error("ShipeasyClient.emitExposure('$experiment') failed: ${it.message}") }
    }

    // ---- Internals ----

    private fun evaluateUser(): Map<String, Any?> = buildMap {
        putAll(userAttributes)
        put("anonymous_id", anonymousId)
        userId?.let { put("user_id", it) }
    }

    private fun stripPrivate(props: Map<String, Any?>): Map<String, Any?> =
        if (privateAttributes.isEmpty()) props else props.filterKeys { it !in privateAttributes }

    /** POST `/sdk/evaluate` for the bound user and apply the assignments. Never throws into caller code. */
    private suspend fun refresh() {
        if (offline) return // fully offline: reads stay on cache / defaults
        runCatching {
            val body = buildMap<String, Any?> {
                put("user", evaluateUser())
                if (privateAttributes.isNotEmpty()) put("private_attributes", privateAttributes)
                if (sticky.isNotEmpty()) put("sticky", sticky)
            }
            val bytes = json.encodeToString(JsonElement.serializer(), toJsonElement(body)).toByteArray()
            val path = "/sdk/evaluate?env=" + URLEncoder.encode(env, StandardCharsets.UTF_8)
            val (status, respBytes) = withContext(Dispatchers.IO) { doPost(path, bytes) }
            if (status != 200) {
                Log.warn("shipeasy: /sdk/evaluate returned HTTP $status")
                return
            }
            @Suppress("UNCHECKED_CAST")
            val root = fromJsonElement(json.parseToJsonElement(String(respBytes, StandardCharsets.UTF_8))) as? Map<String, Any?>
                ?: return
            apply(root)
            ready = true
        }.onFailure { Log.warn("shipeasy: /sdk/evaluate failed: ${it.message}") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun apply(root: Map<String, Any?>) {
        (root["flags"] as? Map<String, Any?>)?.let { f -> flags = f.mapValues { truthy(it.value) } }
        (root["configs"] as? Map<String, Any?>)?.let { configs = it }
        (root["killswitches"] as? Map<String, Any?>)?.let { killswitches = it }
        (root["experiments"] as? Map<String, Any?>)?.let { e ->
            experiments = e.mapNotNull { (name, raw) ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                name to CachedExp(
                    inExperiment = truthy(m["inExperiment"]),
                    group = m["group"]?.toString() ?: "control",
                    params = m["params"] as? Map<String, Any?>,
                    universe = m["universe"] as? String,
                )
            }.toMap()
        }
        (root["universes"] as? Map<String, Any?>)?.let { u ->
            universeDefaults = u.mapNotNull { (name, raw) ->
                val m = raw as? Map<String, Any?> ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val defaults = (m["defaults"] as? Map<String, Any?>) ?: emptyMap<String, Any?>()
                name to defaults
            }.toMap()
        }
        (root["sticky"] as? Map<String, Any?>)?.let { s ->
            sticky = s
            runCatching {
                store.set(STICKY_KEY, json.encodeToString(JsonElement.serializer(), toJsonElement(s)))
            }
        }
    }

    /** Fire-and-forget one event to `/collect` with the client key. No-op offline. */
    private fun send(event: Map<String, Any?>) {
        if (offline) return
        val body = json.encodeToString(JsonElement.serializer(), toJsonElement(mapOf("events" to listOf(event)))).toByteArray()
        scope.launch { runCatching { doPost("/collect", body) } }
    }

    private fun doPost(path: String, body: ByteArray): Pair<Int, ByteArray> {
        transport?.let { return it(path, body) }
        val contentType = if (path.startsWith("/collect")) "text/plain" else "application/json"
        val req = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-SDK-Key", clientKey)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        return res.statusCode() to res.body()
    }

    // ---- JSON interop (mirrors Engine's private helpers; kept local to avoid coupling) ----

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
            e.booleanOrNull != null -> e.boolean
            e.longOrNull != null -> e.longOrNull
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.contentOrNull
        }
        is JsonObject -> e.mapValues { fromJsonElement(it.value) }
        is JsonArray -> e.map { fromJsonElement(it) }
    }

    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() == 1
        is String -> v.equals("true", ignoreCase = true) || v == "1"
        else -> false
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * One cached experiment entry from `/sdk/evaluate`: enrolment, variant,
     * edge-merged params (universe defaults ⊕ variant), and the [universe] the
     * experiment belongs to (so `universe(name).assign()` finds its ≤1 member).
     */
    private data class CachedExp(
        val inExperiment: Boolean,
        val group: String,
        val params: Map<String, Any?>?,
        val universe: String?,
    )

    private companion object {
        const val ANON_KEY = "__se_anon_id"
        const val STICKY_KEY = "__se_sticky"
        private val ANON_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
        fun isValidAnon(v: String?): Boolean = v != null && ANON_RE.matches(v)
    }
}

// ---- Package-global native-client front door ----

private val clientRef = AtomicReference<ShipeasyClient?>(null)
private val clientConfigureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * Configure the process-global native [ShipeasyClient] and kick off an initial
 * anonymous evaluation so flags resolve before an explicit [ShipeasyClient.identify].
 *
 * First-config-wins. Call once at app launch with your **public client key**
 * (never a server key — a server key must never ship in an app binary). Supply an
 * [store] to persist the device `anonymous_id` across launches (on Android use
 * `SharedPreferencesAnonStore` from `ai.shipeasy:shipeasy-kotlin-android`).
 */
@JvmOverloads
fun configureClient(
    clientKey: String,
    store: AnonStore = InMemoryAnonStore(),
    baseUrl: String? = null,
    env: String = "prod",
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    // Master network switch. null ⇒ environment-derived (ON in production, OFF in
    // dev/CI/test): the client stays offline outside production unless you opt in.
    isNetworkEnabled: Boolean? = null,
    // Usage-telemetry switch. null ⇒ environment-derived (same inference).
    isTrackingEnabled: Boolean? = null,
    privateAttributes: List<String> = emptyList(),
): ShipeasyClient {
    clientRef.get()?.let { return it }
    val client = ShipeasyClient(
        clientKey = clientKey,
        baseUrl = baseUrl,
        env = env,
        store = store,
        disableTelemetry = disableTelemetry,
        telemetryUrl = telemetryUrl,
        isNetworkEnabled = isNetworkEnabled,
        isTrackingEnabled = isTrackingEnabled,
        privateAttributes = privateAttributes,
    )
    if (clientRef.compareAndSet(null, client)) {
        clientConfigureScope.launch { runCatching { client.identify(emptyMap()) } }
        return client
    }
    return clientRef.get()!!
}

/** The client built by [configureClient], or null if not yet configured. */
fun shipeasyClient(): ShipeasyClient? = clientRef.get()

/** Test seam: drop the global client so a fresh [configureClient] takes effect. */
internal fun resetClientForTests() { clientRef.set(null) }

/**
 * Test seam: build a [ShipeasyClient] with an injected [transport] (no real
 * network) and install it as the global. Not part of the public surface.
 */
internal fun installClientForTests(
    clientKey: String = "pk_test",
    store: AnonStore = InMemoryAnonStore(),
    env: String = "prod",
    privateAttributes: List<String> = emptyList(),
    transport: ClientTransport,
): ShipeasyClient {
    val client = ShipeasyClient(
        clientKey = clientKey,
        env = env,
        store = store,
        disableTelemetry = true,
        privateAttributes = privateAttributes,
        transport = transport,
    )
    clientRef.set(client)
    return client
}
