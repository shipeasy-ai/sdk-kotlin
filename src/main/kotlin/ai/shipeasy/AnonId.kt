package ai.shipeasy

import java.util.UUID

/**
 * Anonymous bucketing identity — the cross-SDK `__se_anon_id` cookie.
 *
 * Gates and experiments bucket a unit with `murmur3(salt:unit)`. For a
 * logged-out visitor the unit is a stable anonymous id carried in a single
 * first-party cookie that EVERY Shipeasy SDK (server + browser) reads and
 * writes, so a server render and the browser bucket a fractional rollout
 * identically. The cookie name and format are frozen across every language; see
 * `experiment-platform/18-identity-bucketing.md`.
 *
 * These primitives carry no servlet dependency. [AnonIdFilter] wires them into a
 * servlet container; non-servlet stacks (Ktor, http4k, Javalin) can call them
 * directly off their own request/response types.
 */
object AnonId {
    /** The first-party cookie carrying the stable anonymous bucketing unit. */
    const val COOKIE = "__se_anon_id"

    /** One year, in seconds. */
    const val MAX_AGE = 31_536_000

    // The cookie value is client-controllable and feeds bucketing, so a tampered
    // value is treated as absent and a fresh id is minted. UUIDs satisfy this.
    private val VALID = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val currentId = ThreadLocal<String?>()

    /** A fresh opaque bucketing id (UUIDv4). */
    fun mint(): String = UUID.randomUUID().toString()

    fun isValid(value: String?): Boolean = value != null && VALID.matches(value)

    /**
     * The anon id [AnonIdFilter] resolved for the current request, or null when
     * no filter ran. [Engine.getFlag]/[Engine.getExperiment] fall back to this
     * as the default `anonymous_id`.
     */
    fun current(): String? = currentId.get()

    fun setCurrent(id: String?) = currentId.set(id)

    fun clear() = currentId.remove()

    /**
     * Build a `Set-Cookie` header value per the cross-SDK contract. Non-HttpOnly
     * by design — the browser SDK reads it via `document.cookie` to bucket
     * identically to the server.
     */
    fun buildSetCookie(id: String, secure: Boolean): String {
        val sb = StringBuilder("$COOKIE=$id; Path=/; Max-Age=$MAX_AGE; SameSite=Lax")
        if (secure) sb.append("; Secure")
        return sb.toString()
    }
}
