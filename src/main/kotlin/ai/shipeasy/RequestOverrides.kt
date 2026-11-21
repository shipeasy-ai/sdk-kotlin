package ai.shipeasy

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Signed DevTools override cookie (`se_ov`) — verify only. This is a copy of the
// verifier in the shipeasy monorepo's `packages/core/src/overrides/cookie.ts`
// (ported from the TypeScript reference `verifyOverrideCookie` +
// `extractOverrideCookie`) and MUST stay byte-compatible with it: the derivation
// formulas and the signed-body format are a cross-SDK contract guarded by a
// shared fixture test (RequestOverridesTest). Stdlib only (javax.crypto,
// java.util.Base64) plus the kotlinx-serialization already on the classpath.
//
//   project key  Kp = base64url-decoded `overrideKey` from the /sdk/flags payload
//   session key  Ks = HMAC-SHA256(Kp, "se-ov-sess:v1:<nonce>:<exp>")
//   cookie value     = base64url(JSON payload) "." base64url(HMAC-SHA256(Ks, <body text>))

/** The verified override sets from a `se_ov` cookie. `gates` covers flags AND kill switches. */
internal class OverrideMap(
    val gates: Map<String, Boolean>,
    val configs: Map<String, Any?>,
    val exps: Map<String, String>,
)

internal object OverrideCookie {
    const val NAME = "se_ov"
    const val FORMAT_VERSION = 1L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun b64uDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s) // padding optional

    private fun hmac(key: ByteArray, msg: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec rejects an empty key; an empty Kp can never be valid anyway.
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
    }

    private fun str(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Verify a `se_ov` cookie value against the project key [encodedProjectKey]
     * (base64url, as served in the flags payload) and its [keyId]. Fails CLOSED:
     * any malformation, wrong key id, expiry or bad signature yields null — it
     * never throws. [nowSeconds] defaults to now (unix seconds).
     */
    fun verify(
        cookieValue: String,
        encodedProjectKey: String,
        keyId: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): OverrideMap? = try {
        verifyOrThrow(cookieValue, encodedProjectKey, keyId, nowSeconds)
    } catch (_: Exception) {
        null
    }

    private fun verifyOrThrow(cookieValue: String, encodedProjectKey: String, keyId: String, now: Long): OverrideMap? {
        val projectKey = b64uDecode(encodedProjectKey)
        val dot = cookieValue.indexOf('.')
        if (dot <= 0 || dot != cookieValue.lastIndexOf('.')) return null
        val body = cookieValue.substring(0, dot)
        val sig = cookieValue.substring(dot + 1)

        val payload = json.parseToJsonElement(String(b64uDecode(body), Charsets.UTF_8)) as? JsonObject ?: return null
        val v = (payload["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val exp = (payload["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val nonce = str(payload["nonce"])
        val ov = payload["ov"] as? JsonObject
        if (v == null || v != FORMAT_VERSION.toDouble() || str(payload["pid"]) == null ||
            str(payload["keyId"]) == null || nonce == null || exp == null || ov == null
        ) return null
        // `pid` is deliberately not checked: the per-project Kp already binds the
        // cookie to a project, and an SDK doesn't know its own project id.
        if (str(payload["keyId"]) != keyId) return null
        if (exp <= now.toDouble()) return null

        // `exp` is rendered as a plain integer in the signed message (JS number→string).
        val expText = (payload["exp"] as JsonPrimitive).let { p ->
            p.longOrNull?.toString() ?: if (exp == Math.floor(exp)) exp.toLong().toString() else exp.toString()
        }
        val sessionKey = hmac(projectKey, "se-ov-sess:v1:$nonce:$expText")
        val expected = hmac(sessionKey, body)
        if (!MessageDigest.isEqual(expected, b64uDecode(sig))) return null // constant-time

        val gates = (ov["gates"] as? JsonObject).orEmpty()
            .mapNotNull { (k, e) -> (e as? JsonPrimitive)?.takeIf { !it.isString && it.content in setOf("true", "false") }?.let { k to (it.content == "true") } }
            .toMap()
        val configs = (ov["configs"] as? JsonObject).orEmpty().mapValues { fromJson(it.value) }
        val exps = (ov["exps"] as? JsonObject).orEmpty()
            .mapNotNull { (k, e) -> str(e)?.let { k to it } }
            .toMap()
        return OverrideMap(gates, configs, exps)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private fun fromJson(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            e.longOrNull != null -> e.longOrNull
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.content
        }
        is JsonObject -> e.mapValues { fromJson(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.map { fromJson(it) }
    }

    /**
     * Extract the raw `se_ov` value from either the bare cookie value or a full
     * `Cookie:` header (`a=1; se_ov=...; b=2`), percent-decoding the value. Null
     * when absent.
     */
    fun extract(input: String?): String? {
        if (input.isNullOrEmpty()) return null
        if (!input.contains(';') && !input.contains("$NAME=")) {
            return if (input.contains('.')) input else null
        }
        for (part in input.split(';')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            if (part.substring(0, eq).trim() == NAME) {
                return percentDecode(part.substring(eq + 1).trim())
            }
        }
        return null
    }

    // decodeURIComponent semantics: only %XX sequences (UTF-8), '+' stays '+'.
    // A malformed escape yields null (fail closed) instead of throwing.
    private fun percentDecode(s: String): String? {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                val hex = s.substring(i + 1, minOf(i + 3, s.length))
                val b = hex.takeIf { it.length == 2 }?.toIntOrNull(16) ?: return null
                out.write(b)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/**
 * The request-scoped, VERIFIED DevTools overrides. Entered once per request —
 * by [AnonIdFilter] or [withRequestOverrides] — and read by every
 * `Client(user)` read in that scope. Always cleared on exit.
 */
internal object RequestOverrides {
    private val current = ThreadLocal<OverrideMap?>()

    fun current(): OverrideMap? = current.get()

    /** Verify [cookieHeader] against the configured engine; null when nothing valid. */
    fun verified(cookieHeader: String?): OverrideMap? = runCatching {
        val raw = OverrideCookie.extract(cookieHeader) ?: return@runCatching null
        currentEngine()?.verifyOverrideCookie(raw)
    }.getOrNull()

    /** Set the map for this thread; returns the previous one for [restore]. */
    fun enter(map: OverrideMap?): OverrideMap? {
        val prev = current.get()
        current.set(map)
        return prev
    }

    fun restore(prev: OverrideMap?) {
        if (prev == null) current.remove() else current.set(prev)
    }

    /** Coroutine context element that carries [map] across dispatcher hops. */
    fun element(map: OverrideMap?) = current.asContextElement(map)
}

/**
 * Honor the signed DevTools override cookie for every `Client(user)` read inside
 * [block]. Pass the request's raw `Cookie` header (or just the `se_ov` value).
 * The cookie is verified ONCE here, against the project key Shipeasy served with
 * the flags; a missing/invalid/expired cookie (or no key served) means no
 * overrides, never an error. Precedence: `overrideFlag(...)` > signed cookie >
 * normal evaluation. The scope is cleared when [block] returns or throws.
 *
 * Use this when you are not on a servlet stack (where [AnonIdFilter] already does
 * it). For coroutine code use the `suspend` variant [withRequestOverridesSuspend],
 * which keeps the scope across dispatcher switches.
 *
 * ```kotlin
 * withRequestOverrides(call.request.headers["Cookie"]) {
 *     Client(user).getFlag("new_checkout")
 * }
 * ```
 */
fun <T> withRequestOverrides(cookieHeader: String?, block: () -> T): T {
    val prev = RequestOverrides.enter(RequestOverrides.verified(cookieHeader))
    try {
        return block()
    } finally {
        RequestOverrides.restore(prev)
    }
}

/**
 * Suspend-friendly [withRequestOverrides] (a distinct name: same-name overloads are ambiguous for lambdas): the verified overrides travel with the
 * coroutine (`ThreadLocal.asContextElement`), so reads stay overridden after
 * `withContext(Dispatchers.IO)` or any other dispatch inside [block].
 */
suspend fun <T> withRequestOverridesSuspend(cookieHeader: String?, block: suspend () -> T): T =
    withContext(RequestOverrides.element(RequestOverrides.verified(cookieHeader))) { block() }
