package ai.shipeasy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The ergonomic front door: [configure] once at boot, then evaluate per user with
 * `Client(user)`. Mirrors the TypeScript reference (`configure` + `Client`) and
 * the cross-SDK spec — see `.agents/sdk-bound-client-spec.md`.
 *
 * The heavyweight evaluation machinery (HTTP, blob cache, poll timer, overrides,
 * telemetry, see()) lives in [Engine]. [configure] builds ONE [Engine] and stores
 * it as the process-global; the lightweight [Client] reads it and delegates.
 */

/**
 * Transform from your own user object (any shape) into the Shipeasy attribute map
 * (`{ "user_id": ..., "anonymous_id": ..., <targeting attrs> }`) that every flag
 * and experiment evaluation reads. Runs once per `Client(user)` construction.
 */
typealias AttributesFn = (Any?) -> Map<String, Any?>

// Default attributes transform: identity. If the user object is already a map, it
// IS the attribute bag and is used verbatim; anything else becomes empty.
@Suppress("UNCHECKED_CAST")
private val identityAttributes: AttributesFn = { user ->
    if (user is Map<*, *>) user as Map<String, Any?> else emptyMap()
}

@Volatile
private var attributesTransform: AttributesFn = identityAttributes

@Volatile
private var globalEngine: Engine? = null

// First-config-wins guard, mirroring the existing global/default-client logic.
private val configureLock = Any()

// Fire-and-forget scope for the one-shot initOnce() kicked off by configure().
private val configureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

/**
 * Configure the SDK once at app boot, then evaluate per user with `Client(user)`.
 * Builds the process-wide [Engine] (polling + blob cache + HTTP) and registers
 * the [attributes] transform. The first call wins; later calls return the
 * existing engine and leave the transform untouched.
 *
 * The engine's one-shot fetch ([Engine.initOnce]) is kicked off fire-and-forget,
 * so `Client(user).getFlag(...)` resolves against real rules without an explicit
 * `init()`. Long-running servers can instead call `configure(...).init()` to also
 * start the background poll.
 *
 * ```kotlin
 * import ai.shipeasy.configure
 * import ai.shipeasy.Client
 *
 * configure(
 *     apiKey = System.getenv("SHIPEASY_SERVER_KEY"),
 *     attributes = { u -> mapOf("user_id" to (u as MyUser).id, "plan" to u.plan) },
 * )
 *
 * val flags = Client(currentUser)
 * if (flags.getFlag("new_checkout")) { /* … */ }
 * ```
 */
@JvmOverloads
fun configure(
    apiKey: String,
    attributes: AttributesFn? = null,
    baseUrl: String? = null,
    env: String = "prod",
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    privateAttributes: List<String> = emptyList(),
    stickyStore: StickyBucketStore? = null,
    poll: Boolean = false,
): Engine {
    synchronized(configureLock) {
        globalEngine?.let { return it }
        attributesTransform = attributes ?: identityAttributes
        val engine = Engine(
            apiKey = apiKey,
            baseUrl = baseUrl,
            env = env,
            disableTelemetry = disableTelemetry,
            telemetryUrl = telemetryUrl,
            privateAttributes = privateAttributes,
            stickyStore = stickyStore,
        )
        globalEngine = engine
        // Fetch lifecycle owned by configure (the docs never tell a user to call
        // init() themselves): poll → initial fetch + background refresh; default →
        // one-shot fire-and-forget fetch (no-op in localMode).
        configureScope.launch {
            runCatching { if (poll) engine.init() else engine.initOnce() }
        }
        return engine
    }
}

/** The process-global [Engine] built by [configure], or null if not yet configured. */
fun currentEngine(): Engine? = globalEngine

/**
 * Test seam: drop the global engine + reset the attributes transform so a fresh
 * [configure] takes effect. Not part of the public surface customers should use.
 */
internal fun resetConfigureForTests() {
    synchronized(configureLock) {
        globalEngine = null
        attributesTransform = identityAttributes
    }
}

/**
 * Test seam: install a pre-built (e.g. snapshot/offline) [Engine] as the global,
 * with an optional attributes transform, WITHOUT going through configure()'s
 * network-touching `initOnce()`. Mirrors what configure() stores.
 */
internal fun installEngineForTests(engine: Engine, transform: AttributesFn? = null) {
    synchronized(configureLock) {
        globalEngine = engine
        attributesTransform = transform ?: identityAttributes
    }
}

/**
 * A user-bound evaluation handle. Construct one per user/request — it's cheap: it
 * delegates to the [Engine] built by [configure] and does NOT open its own
 * connection, fetch, or poll. The configured [attributes] transform runs once
 * here, so every `getFlag`/`getConfig`/`getExperiment` reads the same bag.
 *
 * The bound attribute bag also picks up the request-scoped `__se_anon_id`
 * (resolved by [AnonIdFilter]) when the user supplied neither `user_id` nor
 * `anonymous_id`, exactly like the per-call [Engine] path.
 *
 * ```kotlin
 * val flags = Client(req.user)
 * flags.getFlag("new_checkout")                 // no user arg — bound at construction
 * flags.getExperiment("price_test", mapOf("price" to 9))
 * ```
 *
 * @throws IllegalStateException if [configure] has not been called yet.
 */
class Client(user: Any?) {
    private val engine: Engine = globalEngine
        ?: throw IllegalStateException(
            "[shipeasy] Client(user) called before configure(apiKey = ...). " +
                "Call configure() once at app boot from ai.shipeasy.",
        )

    /** The resolved attribute bag this handle evaluates against (transform + anon-id). */
    val attributes: Map<String, Any?> = withAnonId(attributesTransform(user))

    /** Read a feature gate. [default] is returned only when the gate can't be evaluated. */
    @JvmOverloads
    fun getFlag(name: String, default: Boolean = false): Boolean =
        engine.getFlag(name, attributes, default)

    /** Read a feature gate with the reason it resolved that way. */
    fun getFlagDetail(name: String): FlagDetail = engine.getFlagDetail(name, attributes)

    /** Read a dynamic config value. [default] is returned when the key is absent. */
    @JvmOverloads
    fun getConfig(name: String, default: Any? = null): Any? = engine.getConfig(name, default)

    /** Evaluate an experiment for the bound user. */
    fun getExperiment(name: String, defaultParams: Any?): ExperimentResult =
        engine.getExperiment(name, attributes, defaultParams)

    /** Read a killswitch (not user-bound; forwards to [Engine.getKillswitch]). */
    @JvmOverloads
    fun getKillswitch(name: String, switchKey: String? = null): Boolean =
        engine.getKillswitch(name, switchKey)

    /**
     * Record a conversion/metric event for the bound user. The unit is derived
     * from the bound attribute bag (`user_id`, else `anonymous_id`); no user arg.
     * Fire-and-forget — forwards to [Engine.track]. A no-op when the bound bag
     * carries no unit. This is the primary path: a single [Client] now covers
     * `getExperiment` AND the conversion it measures.
     */
    @JvmOverloads
    fun track(event: String, props: Map<String, Any?> = emptyMap()) {
        val id = boundUnitId() ?: return
        engine.track(id, event, props)
    }

    /**
     * Emit an exposure event for [experiment] for the bound user (parity with the
     * browser's auto-exposure). The unit is derived from the bound attribute bag;
     * no user arg. Forwards to [Engine.logExposure], which re-evaluates and only
     * emits when the user is enrolled. A no-op when the bound bag carries no unit.
     */
    fun logExposure(experiment: String) {
        val id = boundUnitId() ?: return
        engine.logExposure(id, experiment)
    }

    /** The bound user's unit: `user_id` if present, else `anonymous_id`, else null. */
    private fun boundUnitId(): String? =
        attributes["user_id"]?.toString()?.takeIf { it.isNotEmpty() }
            ?: attributes["anonymous_id"]?.toString()?.takeIf { it.isNotEmpty() }

    private companion object {
        // Mirror Engine.withAnonId: default anonymous_id to the request's
        // __se_anon_id when the caller supplied no explicit unit. A caller-supplied
        // user_id/anonymous_id always wins; a no-op when no AnonIdFilter ran.
        private fun withAnonId(user: Map<String, Any?>): Map<String, Any?> {
            val hasUnit = !user["user_id"]?.toString().isNullOrEmpty() ||
                !user["anonymous_id"]?.toString().isNullOrEmpty()
            val anon = AnonId.current()
            return if (!hasUnit && anon != null) user + ("anonymous_id" to anon) else user
        }
    }
}

// ---- Doc-23 configure() family + package-level helpers ----
//
// The documented surface is exactly configure() (+ these test/offline siblings)
// and the bound Client(user); the heavyweight Engine stays public but
// undocumented. These top-level funs let users avoid naming the Engine in tests,
// overrides, change listeners, and SSR tags.

/**
 * REPLACE the global engine + transform. Unlike [configure] (first-config-wins),
 * the configureFor* siblings replace so a test suite can reconfigure between
 * cases. Closes the previous engine.
 */
private fun installReplace(engine: Engine, transform: AttributesFn?) {
    synchronized(configureLock) {
        globalEngine?.takeIf { it !== engine }?.close()
        globalEngine = engine
        attributesTransform = transform ?: identityAttributes
    }
}

private fun applyOverrides(
    engine: Engine,
    flags: Map<String, Boolean>,
    configs: Map<String, Any?>,
    experiments: Map<String, Pair<String, Any?>>,
) {
    flags.forEach { (name, value) -> engine.overrideFlag(name, value) }
    configs.forEach { (name, value) -> engine.overrideConfig(name, value) }
    experiments.forEach { (name, spec) -> engine.overrideExperiment(name, spec.first, spec.second) }
}

/**
 * Configure Shipeasy in **test mode** — a drop-in sibling of [configure] with no
 * network, ever (no api key needed). Seed [flags] (`name to bool`), [configs]
 * (`name to value`), [experiments] (`name to (group to params)`), then read them
 * through the ordinary `Client(user)`. REPLACES any prior config so tests can
 * reconfigure freely.
 */
@JvmOverloads
fun configureForTesting(
    attributes: AttributesFn? = null,
    flags: Map<String, Boolean> = emptyMap(),
    configs: Map<String, Any?> = emptyMap(),
    experiments: Map<String, Pair<String, Any?>> = emptyMap(),
): Engine {
    val engine = Engine.forTesting()
    applyOverrides(engine, flags, configs, experiments)
    installReplace(engine, attributes)
    return engine
}

/**
 * Configure Shipeasy **offline** — evaluate the REAL rules from an in-memory
 * [snapshot] (`mapOf("flags" to ..., "experiments" to ...)`) or a JSON [path],
 * with no network. Optional [flags]/[configs]/[experiments] overrides layer on
 * top. REPLACES any prior config.
 */
@JvmOverloads
fun configureForOffline(
    snapshot: Map<String, Any?>? = null,
    path: String? = null,
    attributes: AttributesFn? = null,
    flags: Map<String, Boolean> = emptyMap(),
    configs: Map<String, Any?> = emptyMap(),
    experiments: Map<String, Pair<String, Any?>> = emptyMap(),
): Engine {
    @Suppress("UNCHECKED_CAST")
    val engine = when {
        path != null -> Engine.fromFile(path)
        snapshot != null -> Engine.fromSnapshot(
            (snapshot["flags"] as? Map<String, Any?>) ?: emptyMap(),
            (snapshot["experiments"] as? Map<String, Any?>) ?: emptyMap(),
        )
        else -> throw IllegalArgumentException(
            "configureForOffline requires either a snapshot or a path source")
    }
    applyOverrides(engine, flags, configs, experiments)
    installReplace(engine, attributes)
    return engine
}

private fun requireEngine(fn: String): Engine =
    globalEngine ?: throw IllegalStateException(
        "$fn(...) called before configure(...) (or a configureFor* sibling)")

/**
 * Force `getFlag(name)` to [value] on the spot, for the current configuration — a
 * quick in-test override layered on top of whatever [configureForTesting] /
 * [configureForOffline] (or [configure]) set up. Wins over the blob until
 * [clearOverrides].
 */
fun overrideFlag(name: String, value: Boolean) = requireEngine("overrideFlag").overrideFlag(name, value)

/** Force `getConfig(name)` to [value] on the spot (see [overrideFlag]). */
fun overrideConfig(name: String, value: Any?) = requireEngine("overrideConfig").overrideConfig(name, value)

/** Force `getExperiment(name)` to enrol in [group]/[params] on the spot (see [overrideFlag]). */
fun overrideExperiment(name: String, group: String, params: Any?) =
    requireEngine("overrideExperiment").overrideExperiment(name, group, params)

/**
 * Drop every on-the-spot flag/config/experiment override — INCLUDING the seed from
 * [configureForTesting] (test mode has no blob beneath). Under [configureForOffline]
 * evaluations revert to the snapshot.
 */
fun clearOverrides() = requireEngine("clearOverrides").clearOverrides()

/**
 * Register a listener fired after a background poll fetches NEW data. Returns an
 * unsubscribe lambda. Requires `configure(..., poll = true)`.
 */
fun onChange(listener: () -> Unit): () -> Unit = requireEngine("onChange").onChange(listener)

/** SSR bootstrap `<script>` tag for a request (no key embedded), via the configured global engine. */
@JvmOverloads
fun bootstrapScriptTag(
    user: Map<String, Any?>,
    anonId: String? = null,
    i18nProfile: String = "en:prod",
    baseUrl: String? = null,
): String = requireEngine("bootstrapScriptTag").bootstrapScriptTag(user, anonId, i18nProfile, baseUrl)

/** i18n loader `<script>` tag (public client key) for SSR, via the configured global engine. */
@JvmOverloads
fun i18nScriptTag(clientKey: String, profile: String = "en:prod", baseUrl: String? = null): String =
    requireEngine("i18nScriptTag").i18nScriptTag(clientKey, profile, baseUrl)
