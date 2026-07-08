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
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Result of [Engine.getFlagDetail]: the gate value plus the reason for it. */
data class FlagDetail(val value: Boolean, val reason: String)

/**
 * Stable reason codes returned in [FlagDetail.reason] (LaunchDarkly
 * variationDetail parity). String constants so the value is forward-compatible
 * if new reasons are added.
 */
object Reason {
    /** Client not initialized yet — no rules blob loaded. */
    const val CLIENT_NOT_READY = "CLIENT_NOT_READY"

    /** The gate name isn't present in the loaded blob. */
    const val FLAG_NOT_FOUND = "FLAG_NOT_FOUND"

    /** Gate present but disabled or killed (enabled=false / killswitch=true). */
    const val OFF = "OFF"

    /** A local override supplied the value (telemetry skipped). */
    const val OVERRIDE = "OVERRIDE"

    /** The gate evaluated true (rules + rollout passed). */
    const val RULE_MATCH = "RULE_MATCH"

    /** The gate evaluated false (rules or rollout did not pass). */
    const val DEFAULT = "DEFAULT"
}

/**
 * Heavyweight evaluation engine: owns the API key, HTTP client, the cached
 * flags/experiments blobs, the background poll timer, local overrides, telemetry
 * and the see() error reporter. Construct ONE per process — typically via the
 * package-level [configure], which builds it and stores it as the global engine
 * backing the lightweight user-bound [Client]. (Renamed from `Client` in 0.8.0;
 * the name `Client` is now the bound handle.)
 */
class Engine(
    private val apiKey: String,
    baseUrl: String? = null,
    env: String = "prod",
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    // Attribute names usable for targeting but never persisted in analytics
    // (LD/Statsig `privateAttributes`). The server evaluates locally, so private
    // attrs never leave for evaluation at all; the only egress is `/collect`, and
    // the listed keys are stripped from every outbound `track()` payload.
    private val privateAttributes: List<String> = emptyList(),
    // Sticky-bucketing store (doc 20 §2). When provided, `getExperiment` locks a
    // unit to its first-assigned variant — changing allocation % or weights won't
    // re-bucket enrolled units (changing the experiment salt is the reshuffle
    // lever). Absent ⇒ deterministic (fully backward compatible). Built-in:
    // [InMemoryStickyStore].
    private val stickyStore: StickyBucketStore? = null,
    // SDK log verbosity (SILENT < ERROR < WARN < INFO < DEBUG). Sets the level on
    // the shared [Log] helper so every SDK diagnostic is gated. Default WARN.
    private val logLevel: LogLevel = LogLevel.WARN,
    // Local (no-network) test mode. Set only via [forTesting]; init/initOnce/
    // track become no-ops and the client never reaches the network. See the
    // "Testing" section of the README.
    private val localMode: Boolean = false,
) : AutoCloseable {
    private val baseUrl: String = (baseUrl ?: "https://api.shipeasy.ai").trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    // Deployment env, tagged onto see() error events (telemetry already carries
    // it separately on its own beacon path).
    private val env: String = env

    // Per-process spam guard for see(): repeated reports of the same issue within
    // a 30s window collapse to a single send, with a hard per-process cap.
    private val seeLimiter = SeeLimiter()

    // Injectable seam for the see() / track-style /collect POST. Defaults to a
    // fire-and-forget POST on the IO scope (exactly like track()). Same-package
    // [internal] so tests can capture the wire body synchronously without a real
    // network (mirrors Telemetry's injectable sender).
    internal var seeSender: (ByteArray) -> Unit = { body ->
        scope.launch {
            runCatching { post("/collect", body) }
                .onFailure { Log.warn("see() send failed: ${it.message}") }
        }
    }

    // Injectable seam for the track()/logExposure() /collect POST. Mirrors
    // [seeSender]: default is a fire-and-forget POST on the IO scope; tests
    // install a capturing lambda to read the wire body synchronously.
    internal var eventSender: (ByteArray) -> Unit = { body ->
        scope.launch {
            runCatching { post("/collect", body) }
                .onFailure { Log.warn("event send failed: ${it.message}") }
        }
    }

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
    // internal (not private) so same-module tests can mark a network-built engine
    // ready without a fetch (ConfigureClientTest); never exposed to consumers.
    @Volatile internal var initialized = false
    private var pollJob: Job? = null

    // Change listeners. Fire after a background-poll fetch returns NEW data
    // (200, not 304) — never on the initial init() fetch, never in localMode.
    // CopyOnWriteArrayList so iteration during notify is safe under concurrent
    // (un)subscription.
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        // Apply the configured verbosity to the shared leveled logger before any
        // diagnostic can fire (last constructed wins, same as the default engine).
        Log.setLevel(logLevel)
        // Register as the default engine backing the package-level see() funcs
        // (last constructed wins — the server-SDK analog of TS's shipeasy({key})).
        setDefaultClient(this)
    }

    suspend fun init() {
        if (localMode) return
        fetchAll()
        initialized = true
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalSec * 1000L)
                runCatching {
                    // Only a background poll that brought NEW data (200, not 304)
                    // counts as a change — the initial init() fetch above never
                    // notifies, and 304s leave the blobs untouched.
                    if (fetchAll()) notifyChange()
                }.onFailure { Log.warn("poll failed: ${it.message}") }
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

    /**
     * Evaluate a gate and report WHY (value + reason). The reason is computed
     * entirely at this boundary — the canonical eval ([Eval.evalGate]) is
     * untouched. A local override short-circuits BEFORE telemetry, exactly like
     * [getFlag]'s override path; otherwise exactly one "gate" beacon is emitted.
     */
    @Suppress("UNCHECKED_CAST")
    fun getFlagDetail(name: String, user: Map<String, Any?>): FlagDetail = runCatching {
        // 1. Local override wins and skips telemetry (mirrors the override path).
        flagOverrides[name]?.let { return@runCatching FlagDetail(it, Reason.OVERRIDE) }
        // Single telemetry emit for every non-override path (steps 2–5).
        telemetry.emit("gate", name)
        // 2. No rules blob loaded yet.
        val gates = flagsBlob?.get("gates") as? Map<String, Any?>
            ?: return@runCatching FlagDetail(false, Reason.CLIENT_NOT_READY)
        // 3. Gate absent from the loaded blob.
        val gate = gates[name] as? Map<String, Any?>
            ?: return@runCatching FlagDetail(false, Reason.FLAG_NOT_FOUND)
        // 4. Gate present but disabled / killed — read the same fields the
        //    canonical eval reads (killswitch + enabled) so the two never drift.
        if (boolFlag(gate["killswitch"]) || !boolFlag(gate["enabled"])) {
            return@runCatching FlagDetail(false, Reason.OFF)
        }
        // 5. Real evaluation.
        val value = Eval.evalGate(gate, withAnonId(user))
        FlagDetail(value, if (value) Reason.RULE_MATCH else Reason.DEFAULT)
    }.getOrElse {
        // Runtime reads must never throw into the caller — log at error and fall
        // back to the documented "not ready" default (false).
        Log.error("getFlagDetail('$name') threw, returning safe default: ${it.message}")
        FlagDetail(false, Reason.CLIENT_NOT_READY)
    }

    /**
     * Read a feature gate. With [default] it is returned ONLY when the gate
     * cannot be evaluated (client not initialized or flag not found) — never for
     * a gate that legitimately evaluates to false. The 2-arg form keeps
     * returning false for a missing flag.
     */
    fun getFlag(name: String, user: Map<String, Any?>, default: Boolean = false): Boolean = runCatching {
        val d = getFlagDetail(name, user)
        if (d.reason == Reason.CLIENT_NOT_READY || d.reason == Reason.FLAG_NOT_FOUND) default
        else d.value
    }.getOrElse {
        Log.error("getFlag('$name') threw, returning default: ${it.message}")
        default
    }

    // Boundary mirror of Eval's private `enabled` — keeps the OFF check reading
    // the same shape (Boolean or 1/0 Number) the canonical eval reads.
    private fun boolFlag(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() == 1
        else -> false
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

    /**
     * Read a dynamic config value. [default] is returned when the config key is
     * absent (no override and not present in the loaded blob). A `null` override
     * is honoured and wins over [default].
     */
    @Suppress("UNCHECKED_CAST")
    fun getConfig(name: String, default: Any? = null): Any? = runCatching {
        configOverrides[name]?.let { return@runCatching it.value }
        telemetry.emit("config", name)
        val configs = flagsBlob?.get("configs") as? Map<String, Any?> ?: return@runCatching default
        val entry = configs[name] as? Map<String, Any?> ?: return@runCatching default
        entry["value"]
    }.getOrElse {
        Log.error("getConfig('$name') threw, returning default: ${it.message}")
        default
    }

    @Suppress("UNCHECKED_CAST")
    fun getExperiment(name: String, user: Map<String, Any?>, defaultParams: Any?): ExperimentResult = runCatching {
        experimentOverrides[name]?.let { return@runCatching it }
        telemetry.emit("experiment", name)
        val flags = flagsBlob
        val exps = expsBlob
        val exp = (exps?.get("experiments") as? Map<String, Any?>)?.get(name) as? Map<String, Any?>
        val r = Eval.evalExperiment(exp, flags, exps, withAnonId(user), name, stickyStore)
        if (r.params == null) r.copy(params = defaultParams) else r
    }.getOrElse {
        // Documented safe default: not enrolled, control group, caller's params.
        Log.error("getExperiment('$name') threw, returning not-enrolled default: ${it.message}")
        ExperimentResult(false, "control", defaultParams)
    }

    /**
     * Read a killswitch from the loaded flags blob. Without [switchKey], returns
     * true when the whole killswitch is killed. With [switchKey], returns true
     * when that specific per-key override switch is on. Unknown killswitches /
     * switches return false. Not user-scoped (mirrors the TS engine's
     * `getKillswitch`); exposed on [Client] for one-stop ergonomics.
     */
    @Suppress("UNCHECKED_CAST")
    fun getKillswitch(name: String, switchKey: String? = null): Boolean = runCatching {
        telemetry.emit("ks", name)
        val ks = (flagsBlob?.get("killswitches") as? Map<String, Any?>)?.get(name) as? Map<String, Any?>
            ?: return@runCatching false
        if (switchKey == null) return@runCatching boolFlag(ks["killed"])
        // Named-switch semantics (cross-SDK contract): a configured switch key
        // wins; an UNCONFIGURED key falls back to the kill switch's top-level
        // value (so getKillswitch(name, variable) is safe before any per-key
        // override is published).
        val switches = ks["switches"] as? Map<String, Any?>
        if (switches != null && switches.containsKey(switchKey)) {
            return@runCatching boolFlag(switches[switchKey])
        }
        boolFlag(ks["killed"])
    }.getOrElse {
        Log.error("getKillswitch('$name') threw, returning false: ${it.message}")
        false
    }

    /**
     * Batch-evaluate every loaded gate, config and experiment for [user] into a
     * bootstrap payload (`{flags, configs, experiments, killswitches}`) keyed to
     * match the browser SDK's `window.__SE_BOOTSTRAP` shape. Local overrides
     * win. Killswitches are folded into per-gate evaluation, so the standalone
     * `killswitches` map is empty for this SDK. No telemetry (a batch evaluate
     * is not a per-flag exposure).
     */
    @Suppress("UNCHECKED_CAST")
    fun evaluate(user: Map<String, Any?>): Map<String, Any?> {
        val u = withAnonId(user)
        val flags = flagsBlob
        val exps = expsBlob

        val outFlags = LinkedHashMap<String, Any?>()
        val outConfigs = LinkedHashMap<String, Any?>()
        val outExps = LinkedHashMap<String, Any?>()

        (flags?.get("gates") as? Map<String, Any?>)?.forEach { (name, gate) ->
            outFlags[name] = flagOverrides[name] ?: Eval.evalGate(gate as Map<String, Any?>, u)
        }
        (flags?.get("configs") as? Map<String, Any?>)?.forEach { (name, entry) ->
            outConfigs[name] =
                if (configOverrides.containsKey(name)) configOverrides[name]?.value
                else (entry as? Map<String, Any?>)?.get("value")
        }
        (exps?.get("experiments") as? Map<String, Any?>)?.forEach { (name, exp) ->
            val r = experimentOverrides[name]
                ?: Eval.evalExperiment(exp as? Map<String, Any?>, flags, exps, u, name, stickyStore)
            outExps[name] = linkedMapOf<String, Any?>(
                "inExperiment" to r.inExperiment,
                "group" to r.group,
                "params" to r.params,
            )
        }

        return linkedMapOf(
            "flags" to outFlags,
            "configs" to outConfigs,
            "experiments" to outExps,
            "killswitches" to LinkedHashMap<String, Any?>(),
        )
    }

    /**
     * Return the cross-platform SSR bootstrap `<script>` tag for a request:
     * se-bootstrap.js reads its `data-*` attributes and hydrates
     * `window.__SE_BOOTSTRAP` (and writes the anon cookie). No SDK key is
     * embedded — the server key must never reach the browser.
     */
    @JvmOverloads
    fun bootstrapScriptTag(
        user: Map<String, Any?>,
        anonId: String? = null,
        i18nProfile: String = "en:prod",
        baseUrl: String? = null,
    ): String {
        val payload = evaluate(user)
        val base = cdnBase(baseUrl)
        val profile = i18nProfile.ifEmpty { "en:prod" }
        val attrs = StringBuilder("data-se-bootstrap ")
        attrs.append(attr("data-flags", jsonStr(payload["flags"]))).append(' ')
        attrs.append(attr("data-configs", jsonStr(payload["configs"]))).append(' ')
        attrs.append(attr("data-experiments", jsonStr(payload["experiments"]))).append(' ')
        attrs.append(attr("data-killswitches", jsonStr(payload["killswitches"]))).append(' ')
        attrs.append(attr("data-i18n-profile", profile)).append(' ')
        attrs.append(attr("data-api-url", base))
        if (!anonId.isNullOrEmpty()) attrs.append(' ').append(attr("data-anon-id", anonId))
        return "<script src=\"${escapeAttr("$base/sdk/bootstrap.js")}\" $attrs></script>"
    }

    /**
     * Return the i18n loader `<script>` tag. The loader fetches translations for
     * the profile using the PUBLIC client key (safe to embed in HTML).
     */
    @JvmOverloads
    fun i18nScriptTag(clientKey: String, profile: String = "en:prod", baseUrl: String? = null): String {
        val base = cdnBase(baseUrl)
        val p = profile.ifEmpty { "en:prod" }
        return "<script src=\"${escapeAttr("$base/sdk/i18n/loader.js")}\" " +
            "${attr("data-key", clientKey)} ${attr("data-profile", p)}></script>"
    }

    private fun cdnBase(override: String?): String =
        (if (override.isNullOrEmpty()) DEFAULT_CDN_BASE else override).trimEnd('/')

    private fun jsonStr(v: Any?): String =
        runCatching { json.encodeToString(JsonElement.serializer(), toJsonElement(v)) }.getOrDefault("{}")

    private fun attr(name: String, value: String): String = "$name=\"${escapeAttr(value)}\""

    private fun escapeAttr(v: String): String =
        v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * Drop caller-marked private attributes from an outbound props bag. A no-op
     * when no private attributes are configured or the bag is empty.
     */
    private fun stripPrivate(properties: Map<String, Any?>?): Map<String, Any?>? {
        if (properties == null || privateAttributes.isEmpty()) return properties
        return properties.filterKeys { it !in privateAttributes }
    }

    fun track(userId: String, eventName: String, properties: Map<String, Any?>? = null) {
        if (localMode) return
        // Fire-and-forget: an unexpected throwable must never surface to the caller.
        runCatching {
            val safeProps = stripPrivate(properties)
            val event = buildMap<String, Any?> {
                put("type", "metric"); put("event_name", eventName); put("user_id", userId)
                put("ts", Instant.now().toEpochMilli())
                if (!safeProps.isNullOrEmpty()) put("properties", safeProps)
            }
            val body = mapOf("events" to listOf(event))
            eventSender(json.encodeToString(JsonElement.serializer(), toJsonElement(body)).toByteArray())
        }.onFailure { Log.error("track('$eventName') failed: ${it.message}") }
    }

    /**
     * Emit an exposure event for an experiment at the server-side decision point
     * (parity with the browser's auto-exposure). The server is stateless and
     * never auto-logs, so call this when you actually present the treatment.
     * Re-evaluates [experimentName] for [userId]; if the user is enrolled, POSTs
     * a single `exposure` event to `/collect`. No-op in localMode or when the
     * user isn't enrolled.
     */
    fun logExposure(userId: String, experimentName: String) {
        if (localMode) return
        // Fire-and-forget: never surface a throwable to the caller.
        runCatching {
            val result = getExperiment(experimentName, mapOf("user_id" to userId), null)
            if (!result.inExperiment) return
            val event = buildMap<String, Any?> {
                put("type", "exposure"); put("experiment", experimentName)
                put("group", result.group); put("user_id", userId)
                put("ts", Instant.now().toEpochMilli())
            }
            val body = mapOf("events" to listOf(event))
            eventSender(json.encodeToString(JsonElement.serializer(), toJsonElement(body)).toByteArray())
        }.onFailure { Log.error("logExposure('$experimentName') failed: ${it.message}") }
    }

    // ---- see() structured error reporting ----

    /**
     * Report a caught throwable (or thrown non-throwable). Fire-and-forget; never
     * blocks or throws into the request path. Terminate with `to(outcome)`:
     *
     * ```kotlin
     * engine.see(e).causesThe("checkout").to("use cached prices")
     * ```
     */
    fun see(problem: Any?): SeeChain = SeeChain(problem, ::dispatchSee)

    /**
     * Report a non-exception problem. The name is a stable fingerprint key — put
     * variable data in `extras()`, never in the name.
     */
    fun seeViolation(name: String): SeeChain = SeeChain(Violation(name), ::dispatchSee)

    /** Mark an exception as expected control flow — reports nothing. */
    fun controlFlowException(err: Throwable): ControlFlowChain = ControlFlowChain(err)

    // Build the wire event and fire-and-forget POST it to /collect. No-op in
    // localMode. Spam-guarded. Never raises into caller code.
    private fun dispatchSee(built: BuiltSee) {
        if (localMode) return
        runCatching {
            val ev = buildSeeEvent(
                built.problem,
                built.subject,
                built.outcome,
                stripPrivate(built.extras),
                side = "server",
                sdkVersion = VERSION,
                env = env,
            )
            if (!seeLimiter.shouldSend(ev)) return
            val body = mapOf("events" to listOf(ev))
            seeSender(json.encodeToString(JsonElement.serializer(), toJsonElement(body)).toByteArray())
        }.onFailure { Log.error("see() send failed: ${it.message}") }
    }

    /** Returns true if either blob was refreshed with new data (200, not 304). */
    private suspend fun fetchAll(): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        val flagsRes = httpGet("/sdk/flags", flagsEtag)
        flagsRes.headers().firstValue("X-Poll-Interval").ifPresent {
            it.toIntOrNull()?.let { v -> pollIntervalSec = v }
        }
        when (flagsRes.statusCode()) {
            200 -> {
                flagsRes.headers().firstValue("ETag").ifPresent { flagsEtag = it }
                applyFlags(String(flagsRes.body()))
                changed = true
            }
            304 -> {}
            else -> error("/sdk/flags: ${flagsRes.statusCode()}")
        }
        val expsRes = httpGet("/sdk/experiments", expsEtag)
        when (expsRes.statusCode()) {
            200 -> {
                expsRes.headers().firstValue("ETag").ifPresent { expsEtag = it }
                applyExps(String(expsRes.body()))
                changed = true
            }
            304 -> {}
            else -> error("/sdk/experiments: ${expsRes.statusCode()}")
        }
        changed
    }

    // Decode + store the body of /sdk/flags (resp. /sdk/experiments). Factored
    // out of fetchAll so the wire-decode path is named and reusable.
    @Suppress("UNCHECKED_CAST")
    private fun applyFlags(body: String) {
        flagsBlob = fromJsonElement(json.parseToJsonElement(body)) as? Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyExps(body: String) {
        expsBlob = fromJsonElement(json.parseToJsonElement(body)) as? Map<String, Any?>
    }

    /**
     * Subscribe to data-change notifications. The listener fires after a
     * background poll fetch returns NEW data (200, not 304) — i.e. after the
     * cached blobs are refreshed. Never fires in localMode (no polling) and not
     * for the initial [init] fetch. Returns an unsubscribe function.
     */
    fun onChange(listener: () -> Unit): () -> Unit {
        changeListeners.add(listener)
        return { changeListeners.remove(listener) }
    }

    private fun notifyChange() {
        for (l in changeListeners) {
            runCatching { l() }.onFailure { Log.warn("onChange listener threw: ${it.message}") }
        }
    }

    /**
     * Test-only seam: simulate a background poll that brought NEW data — replace
     * the cached blobs and fire change listeners, exactly as the poll loop does
     * on a 200. Lets onChange be exercised without a real network. Same-package
     * [internal] visibility; not part of the public API.
     */
    internal fun applyDataForTest(
        flags: Map<String, Any?>? = flagsBlob,
        experiments: Map<String, Any?>? = expsBlob,
    ) {
        flagsBlob = flags
        expsBlob = experiments
        notifyChange()
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
         * CDN origin serving the static loader scripts (`/sdk/bootstrap.js`,
         * `/sdk/i18n/loader.js`) — distinct from the edge API the blobs come from.
         */
        private const val DEFAULT_CDN_BASE = "https://cdn.shipeasy.ai"

        /**
         * Build a no-network engine for tests. Telemetry is disabled, `init()`/
         * `initOnce()`/`track()` are no-ops (never reach the network), and no API
         * key is required. Seed values with [overrideFlag]/[overrideConfig]/
         * [overrideExperiment]; entities with no override fall back to their
         * defaults (flag → false, config → null, experiment → not in experiment).
         */
        @JvmStatic
        fun forTesting(): Engine = Engine(apiKey = "", disableTelemetry = true, localMode = true)
            .also { it.initialized = true }

        /**
         * Build a fully OFFLINE engine from pre-captured blobs — no network ever.
         * Reuses the [localMode] plumbing (`init()`/`initOnce()`/`track()` are
         * no-ops, telemetry off) but, unlike [forTesting], seeds the REAL flags +
         * experiments blobs so evaluations run the canonical [Eval] against the
         * snapshot. Local overrides still apply on top.
         *
         * @param flags the body of `GET /sdk/flags` (a map with `gates`/`configs`)
         * @param experiments the body of `GET /sdk/experiments` (a map with
         *   `experiments`/`universes`)
         */
        @JvmStatic
        @JvmOverloads
        fun fromSnapshot(
            flags: Map<String, Any?>,
            experiments: Map<String, Any?>,
            // Optional sticky-bucketing store. Threaded into experiment eval so an
            // offline engine can exercise sticky assignments. Absent ⇒ deterministic.
            stickyStore: StickyBucketStore? = null,
        ): Engine =
            Engine(
                apiKey = "",
                disableTelemetry = true,
                stickyStore = stickyStore,
                localMode = true,
            ).also {
                it.flagsBlob = flags
                it.expsBlob = experiments
                it.initialized = true
            }

        /**
         * Build a fully OFFLINE engine from a snapshot JSON file on disk. The file
         * must contain `{ "flags": <GET /sdk/flags body>, "experiments": <GET
         * /sdk/experiments body> }`. See [fromSnapshot].
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun fromFile(path: String): Engine {
            val snapshotJson = Json { ignoreUnknownKeys = true; isLenient = true }
            val raw = String(Files.readAllBytes(Paths.get(path)))
            val root = snapshotJson.parseToJsonElement(raw) as? JsonObject
                ?: error("snapshot file is not a JSON object: $path")
            val flags = (decode(root["flags"]) as? Map<String, Any?>) ?: emptyMap()
            val experiments = (decode(root["experiments"]) as? Map<String, Any?>) ?: emptyMap()
            return fromSnapshot(flags, experiments)
        }

        // Same JSON → Kotlin coercion the instance fetch path uses, hoisted so the
        // companion can decode a snapshot file without an instance.
        private fun decode(e: JsonElement?): Any? = when (e) {
            null, is JsonNull -> null
            is JsonPrimitive -> when {
                e.isString -> e.contentOrNull
                e.contentOrNull?.lowercase() == "true" -> true
                e.contentOrNull?.lowercase() == "false" -> false
                e.longOrNull != null -> e.longOrNull
                e.doubleOrNull != null -> e.doubleOrNull
                else -> e.contentOrNull
            }
            is JsonObject -> e.mapValues { decode(it.value) }
            is JsonArray -> e.map { decode(it) }
        }
    }
}
