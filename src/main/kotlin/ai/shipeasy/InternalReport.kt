package ai.shipeasy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Internal self-monitoring channel — SDK bugs that are "on our end".
 *
 * When the SDK swallows one of its OWN internal errors (the last-resort guards
 * in [Engine]'s runtime readers — `getFlagDetail`/`getConfig`/`getExperiment`/
 * `getKillswitch`, which keep a read from throwing into product code even when
 * an internal invariant is violated), it ALSO ships a structured see event here
 * — to Shipeasy's OWN project, NOT the consumer's — so the SDK team can track
 * SDK-internal failures across every app the SDK runs in.
 *
 * This is deliberately distinct from the customer-facing `see()` path
 * ([Engine.see]), which authenticates with the consumer's key and lands in the
 * consumer's dashboard. Internal errors must never pollute a customer's Errors
 * tab, and the SDK team must see them centrally — so this channel has its own
 * baked-in destination + credential.
 *
 * Guarantees (identical to telemetry/see): fire-and-forget, never blocks, never
 * throws into product code, deduped/rate-limited. A failed send is swallowed
 * silently — it must never log (that would risk recursion through the guard).
 */
internal object InternalReport {
    // ---- Baked-in destination ----
    //
    // The main Shipeasy project (`.shipeasy`). The credential is a PUBLIC client
    // key — the same class of credential already embedded verbatim in every
    // browser bundle that ships the client SDK — so baking it into the published
    // package is safe. `/collect` treats it as a write-only ingest key; it grants
    // no read access. The canonical ingest host is api.shipeasy.ai (the SDK
    // default baseUrl), which routes /collect to the edge worker.
    const val INGEST_URL: String = "https://api.shipeasy.ai/collect"

    // Sentinel used until the real key is minted + baked. While [ingestKey] is
    // still the placeholder the channel stays fully inert (see [report]), so a
    // build that ships before the key is provisioned never fires doomed requests.
    // Mint the key with:
    //   shipeasy keys create --type client --env prod \
    //     --name "SDK internal error self-reporting" --scopes events:write
    // then replace the [ingestKey] initializer below with the returned value.
    const val PLACEHOLDER_KEY: String = "sdk_client_REPLACE_WITH_SHIPEASY_INTERNAL_ERROR_KEY"

    // The baked ingest credential. Swap the placeholder for the real minted key
    // here (this is the only value that needs to change once the key exists).
    @Volatile
    private var ingestKey: String = "sdk_client_00bd4608a03e4084922978f9522614d5"

    // Stable consequence. The `label` (the guard's operation name, e.g.
    // "flags.get") is the subject; the outcome is fixed. Both are constant per
    // operation — no variable data — so occurrences of the same internal bug fold
    // into one issue on our dashboard (fingerprint = error_type + normalized
    // message + top stack + subject|outcome). `sdk` marks the reporting language.
    private const val OUTCOME = "returned a safe default"
    private const val SDK_ID = "kotlin"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Module-level context, set once per process from the Engine constructor —
    // mirrors how [Log.setLevel] carries the level. `enabled` defaults on; it is
    // forced off in localMode (test/offline) and when the caller opts out via
    // `disableInternalErrorReporting`. Null until configured (a report before an
    // Engine exists is a no-op — nothing to attribute it to).
    private class Ctx(val side: String, val sdkVersion: String, val enabled: Boolean)

    @Volatile
    private var ctx: Ctx? = null

    // Bounds network chatter from a hot internal-error loop (30s dedup window + a
    // hard per-process cap). The backend dedupes by fingerprint anyway.
    @Volatile
    private var limiter = SeeLimiter()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    // The real network sender: a fire-and-forget POST on a daemon thread. A
    // failed send is swallowed silently — never log (that would risk recursion
    // through the guard that reports here). Held as a named val so [resetForTest]
    // can restore it after a test installs a capturing lambda.
    private val networkSender: (ByteArray) -> Unit = { body ->
        val t = Thread {
            runCatching {
                val req = HttpRequest.newBuilder(URI.create(INGEST_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-SDK-Key", ingestKey)
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.discarding())
            }
        }
        t.isDaemon = true
        t.start()
    }

    // Injectable sender seam. Tests install a capturing lambda to read the wire
    // body synchronously without a real network (mirrors Engine.seeSender).
    @Volatile
    internal var sender: (ByteArray) -> Unit = networkSender

    /** True once a real key has been baked in (not the placeholder sentinel). */
    private fun keyConfigured(): Boolean = ingestKey.isNotEmpty() && ingestKey != PLACEHOLDER_KEY

    /**
     * Wire the self-monitoring channel. Called from the [Engine] constructor with
     * the bundle's side + version. [enabled] defaults on; it is forced off in
     * localMode (no network) and when the caller opts out.
     */
    fun setContext(side: String, sdkVersion: String, enabled: Boolean = true) {
        ctx = Ctx(side, sdkVersion, enabled)
    }

    /**
     * Report an SDK-internal error to Shipeasy's own project. Called from a
     * runtime reader's last-resort guard. [label] is the swallowed operation
     * (e.g. "flags.get") and becomes the stable issue subject. Never throws.
     */
    fun report(label: String, err: Any?) {
        runCatching {
            val c = ctx ?: return
            if (!c.enabled || !keyConfigured()) return
            val ev = buildSeeEvent(
                err,
                subject = label,
                outcome = OUTCOME,
                extras = mapOf("sdk" to SDK_ID),
                side = c.side,
                sdkVersion = c.sdkVersion,
                // No consumer env/url — this is SDK-side context only, so an
                // internal report never carries a customer's deployment env.
                env = null,
            )
            if (!limiter.shouldSend(ev)) return
            val body = mapOf<String, Any?>("events" to listOf(ev))
            sender(json.encodeToString(JsonElement.serializer(), toJsonElement(body)).toByteArray())
        }.onFailure { /* self-reporting must never throw into product code */ }
    }

    // Same JSON coercion Engine uses for /collect bodies, inlined so this channel
    // stays self-contained.
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

    // ---- Test seams ----

    /** Reset context + limiter + key + sender so a spec starts from a clean, inert channel. */
    internal fun resetForTest() {
        ctx = null
        limiter = SeeLimiter()
        ingestKey = PLACEHOLDER_KEY
        sender = networkSender
    }

    /** Stand in a real-looking key so specs can exercise the send path (the baked
     *  default is the deliberately inert placeholder). */
    internal fun setIngestKeyForTest(key: String) {
        ingestKey = key
    }
}
