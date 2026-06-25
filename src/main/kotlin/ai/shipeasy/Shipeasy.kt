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
        // Kick off the one-shot fetch (no-op in localMode) so the first
        // Client(user).getFlag(...) resolves against real rules.
        configureScope.launch {
            runCatching { engine.initOnce() }
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
