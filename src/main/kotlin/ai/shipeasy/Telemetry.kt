package ai.shipeasy

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-evaluation usage telemetry. Fires one fire-and-forget HTTP beacon per
 * evaluation so usage is counted by Cloudflare's native per-path analytics.
 * Mirrors the contract in the TypeScript reference SDK and
 * experiment-platform/15-usage-metering.md. The path carries sha256(apiKey) --
 * never the raw key -- plus side/env, then feature/resource. The 2s dedup
 * window bounds volume under loops.
 */
internal class Telemetry(
    endpoint: String,
    sdkKey: String,
    side: String = "server",
    env: String = "prod",
    disabled: Boolean = false,
    private val sender: (String) -> Unit,
) {
    private val dedupeMs = 2000L
    private val last = ConcurrentHashMap<String, Long>()
    private val disabled: Boolean
    private val prefix: String

    init {
        val ep = endpoint.trimEnd('/')
        this.disabled = disabled || sdkKey.isEmpty() || ep.isEmpty()
        prefix = if (this.disabled) "" else "$ep/t/${sha256Hex(sdkKey)}/$side/${enc(env)}"
    }

    /** Production constructor: the sender does a fire-and-forget HTTP GET. */
    constructor(endpoint: String, sdkKey: String, side: String, env: String, disabled: Boolean, http: HttpClient) :
        this(endpoint, sdkKey, side, env, disabled, httpSender(http))

    /** Best-effort usage beacon for one evaluation. Never blocks, never throws. */
    fun emit(feature: String, resource: String) {
        if (disabled) return
        if (dedupeMs > 0) {
            val key = "$feature/$resource"
            val now = System.currentTimeMillis()
            val prev = last[key]
            if (prev != null && now - prev < dedupeMs) return
            last[key] = now
        }
        sender("$prefix/$feature/${enc(resource)}")
    }

    // encodeURIComponent-equivalent: %20 for space (URLEncoder emits "+").
    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

// Default sender: fire-and-forget HTTP GET. Top-level so the secondary
// constructor can reference it in its delegation without touching instance state.
private fun httpSender(http: HttpClient): (String) -> Unit = { url ->
    runCatching {
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
    }
}
