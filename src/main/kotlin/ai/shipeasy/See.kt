package ai.shipeasy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * see — shipeasy error. Structured error reporting for the server SDK.
 *
 * Mirrors `@shipeasy/sdk` (`packages/ts-sdk/src/see/core.ts`) and the Python
 * reference (`sdk-python/shipeasy/_see.py`). Every handled exception documents
 * its product *consequence*, not just its stack:
 *
 * ```kotlin
 * import ai.shipeasy.see
 *
 * try {
 *     chargeCard(order)
 * } catch (e: Exception) {
 *     see(e).causesThe("checkout").extras(mapOf("order_id" to order.id))
 *         .to("use the backup processor")
 * }
 * ```
 *
 * Dispatch model (differs from TS, which uses a microtask): `to(outcome)` is
 * the terminal — it builds the wire event and fire-and-forgets the POST to
 * `/collect`. `causesThe()` and `extras()` are chainable setters that may be
 * called in any order *before* `to()`. If you never call `to()`, nothing is
 * sent. Reporting never blocks and never throws into caller code.
 *
 * If you don't know the consequence of an exception, don't catch it.
 */

/** The single runtime source of `sdk_version` on every see() wire event. */
const val VERSION: String = "0.12.1"

// ---- Limits (mirror core.ts; kept in sync with the worker's /collect) ----
internal const val SEE_MAX_MESSAGE = 500
internal const val SEE_MAX_STACK = 8000
internal const val SEE_MAX_SUBJECT = 200
internal const val SEE_MAX_EXTRA_VALUE = 200
internal const val SEE_MAX_EXTRA_KEYS = 20
internal const val SEE_DEDUP_WINDOW_MS = 30_000L
internal const val SEE_MAX_PER_PROCESS = 25

// Default consequence parts when a chain omits them.
internal const val SEE_DEFAULT_SUBJECT = "app"
internal const val SEE_DEFAULT_OUTCOME = "hit an error"

private fun truncate(s: String, limit: Int): String = if (s.length <= limit) s else s.substring(0, limit)

/**
 * Drop null values, keep only String / finite Number / Boolean, truncate string
 * values to 200 chars, cap at 20 keys (insertion order). Returns null if nothing
 * is kept. Mirrors `sanitize_extras` in core.ts.
 */
internal fun sanitizeExtras(extras: Map<String, Any?>?): Map<String, Any?>? {
    if (extras.isNullOrEmpty()) return null
    val out = LinkedHashMap<String, Any?>()
    var n = 0
    for ((k, v) in extras) {
        if (v == null) continue
        if (n >= SEE_MAX_EXTRA_KEYS) break
        when (v) {
            is Boolean -> out[k] = v
            is String -> out[k] = truncate(v, SEE_MAX_EXTRA_VALUE)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) continue
                out[k] = v
            }
            else -> continue
        }
        n += 1
    }
    return if (out.isEmpty()) null else out
}

/**
 * A non-exception problem. The [name] is a stable fingerprint key — put variable
 * data in `extras()`, never in the name.
 */
class Violation(val name: String)

// Marker key stamped onto an exception by controlFlowException(). A WeakHashMap
// keyed by identity lets us mark frozen/builtin throwables we can't mutate.
private val expectedMarks =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Throwable, Map<String, Any?>>())

/** Best-effort: mark a throwable as expected control flow (for local debug). */
internal fun markExpected(err: Throwable, because: String, extras: Map<String, Any?>? = null) {
    runCatching {
        val mark = LinkedHashMap<String, Any?>()
        mark["because"] = because
        sanitizeExtras(extras)?.let { mark["extras"] = it }
        expectedMarks[err] = mark
    }
}

/** True if [err] was marked expected via [controlFlowException]`.because(...)`. */
fun isExpected(err: Throwable): Boolean = expectedMarks[err] != null

// ---- Wire event construction ----

/**
 * Build the `type:"error"` event accepted by POST /collect. Returns an ordered
 * map so JSON key order matches the other SDKs. Mirrors `build_see_event`.
 */
internal fun buildSeeEvent(
    problem: Any?,
    subject: String,
    outcome: String,
    extras: Map<String, Any?>?,
    side: String,
    sdkVersion: String,
    env: String?,
): Map<String, Any?> {
    val errorType: String
    val message: String
    var stack: String? = null
    val kind: String
    when (problem) {
        is Violation -> {
            errorType = problem.name
            message = problem.name
            kind = "violation"
        }
        is Throwable -> {
            errorType = problem::class.simpleName ?: "Error"
            message = problem.message ?: errorType
            stack = runCatching { problem.stackTraceToString().trim().ifEmpty { null } }.getOrNull()
            kind = "caught"
        }
        else -> {
            errorType = "Error"
            message = problem as? String ?: problem.toString()
            kind = "caught"
        }
    }

    val ev = LinkedHashMap<String, Any?>()
    ev["type"] = "error"
    ev["kind"] = kind
    ev["error_type"] = truncate(errorType, SEE_MAX_SUBJECT)
    ev["message"] = truncate(message, SEE_MAX_MESSAGE)
    ev["subject"] = truncate(subject, SEE_MAX_SUBJECT)
    ev["outcome"] = truncate(outcome, SEE_MAX_SUBJECT)
    stack?.let { ev["stack"] = truncate(it, SEE_MAX_STACK) }
    sanitizeExtras(extras)?.let { ev["extras"] = it }
    ev["side"] = side
    if (!env.isNullOrEmpty()) ev["env"] = env
    ev["sdk_version"] = sdkVersion
    ev["ts"] = System.currentTimeMillis()
    return ev
}

// ---- Spam limiter (mirror SeeLimiter) ----

private fun topStackLine(stack: String?): String {
    if (stack.isNullOrEmpty()) return ""
    for (raw in stack.lineSequence()) {
        val s = raw.trim()
        if (s.startsWith("at ") || s.startsWith("File ") || s.contains("line ")) {
            return if (s.length > 200) s.substring(0, 200) else s
        }
    }
    return ""
}

/**
 * Per-process spam guard: identical events within 30s collapse to one send, and
 * a hard cap bounds total sends per process lifetime. Thread-safe. The worker
 * dedupes by fingerprint anyway — this only bounds network chatter from a hot
 * loop.
 */
internal class SeeLimiter(
    private val maxPerProcess: Int = SEE_MAX_PER_PROCESS,
    private val windowMs: Long = SEE_DEDUP_WINDOW_MS,
) {
    private val last = ConcurrentHashMap<String, Long>()
    private val sent = AtomicInteger(0)

    @Synchronized
    fun shouldSend(ev: Map<String, Any?>): Boolean {
        if (sent.get() >= maxPerProcess) return false
        val key = listOf(
            ev["kind"].toString(),
            ev["error_type"].toString(),
            (ev["message"]?.toString() ?: "").take(200),
            topStackLine(ev["stack"] as? String),
        ).joinToString("|")
        val now = System.currentTimeMillis()
        val prev = last[key]
        if (prev != null && now - prev < windowMs) return false
        last[key] = now
        sent.incrementAndGet()
        return true
    }
}

// ---- Fluent chains ----

/**
 * Accumulates the consequence + extras for a see() report; `to(outcome)`
 * dispatches once. `causesThe()` and `extras()` may be called in any order
 * before `to()`. Calling `to()` twice is a no-op.
 */
class SeeChain internal constructor(
    private val problem: Any?,
    private val dispatch: (BuiltSee) -> Unit,
) {
    private var subject: String? = null
    private var outcome: String? = null
    private var extras: LinkedHashMap<String, Any?>? = null
    private var done = false

    /** Name the thing affected by the error (the consequence subject). */
    fun causesThe(subject: String): SeeChain {
        this.subject = subject
        return this
    }

    /** Merge structured context (later wins on repeat keys). */
    fun extras(map: Map<String, Any?>): SeeChain {
        if (map.isNotEmpty()) {
            val merged = extras ?: LinkedHashMap()
            merged.putAll(map)
            extras = merged
        }
        return this
    }

    /** Terminal: build the event and fire-and-forget the report. Idempotent. */
    fun to(outcome: String) {
        if (done) return
        done = true
        this.outcome = outcome
        runCatching {
            dispatch(
                BuiltSee(
                    problem,
                    subject ?: SEE_DEFAULT_SUBJECT,
                    outcome.ifEmpty { SEE_DEFAULT_OUTCOME },
                    extras,
                ),
            )
        }.onFailure { /* reporting must never raise into caller code */ }
    }
}

/** A finalized chain handed to the client dispatcher. */
internal class BuiltSee(
    val problem: Any?,
    val subject: String,
    val outcome: String,
    val extras: Map<String, Any?>?,
)

/**
 * `controlFlowException(e).because("because ...")` — marks the exception expected
 * and reports NOTHING. `.extras()` is stored for local debugging only (an
 * expected exception is never transmitted).
 */
class ControlFlowChain internal constructor(private val err: Throwable) {
    fun because(reason: String): ControlFlowTail {
        markExpected(err, reason)
        return ControlFlowTail(err, reason)
    }
}

class ControlFlowTail internal constructor(private val err: Throwable, private val reason: String) {
    fun extras(map: Map<String, Any?>): ControlFlowTail {
        markExpected(err, reason, map)
        return this
    }
}

// ---- Global default engine ----

@Volatile
private var defaultClient: Engine? = null

/**
 * Register the [Engine] backing the package-level [see]/[seeViolation]/
 * [controlFlowException] functions. Called automatically when an [Engine] is
 * constructed (last constructed wins — the server-SDK analog of TS's
 * `shipeasy({key})` configure). The lightweight user-bound [Client] does NOT
 * register here — only the heavyweight engine does.
 */
fun setDefaultClient(client: Engine?) {
    defaultClient = client
}

/**
 * Report a caught throwable (or thrown non-throwable) via the default engine.
 * Use `engine.see()` to target a specific engine. Calling this before any engine
 * exists logs a warning and returns a no-op chain (never throws).
 */
fun see(problem: Any?): SeeChain {
    val c = defaultClient
    if (c == null) {
        Log.warn("see() called before configure()/an Engine was created — error dropped")
        return SeeChain(problem) { }
    }
    return c.see(problem)
}

/** Report a non-exception problem via the default client. */
fun seeViolation(name: String): SeeChain {
    val c = defaultClient
    if (c == null) {
        Log.warn("seeViolation() called before configure()/an Engine was created — error dropped")
        return SeeChain(Violation(name)) { }
    }
    return c.seeViolation(name)
}

/**
 * Mark an exception as expected control flow (reports nothing). Works without a
 * client — it only marks the throwable.
 */
fun controlFlowException(err: Throwable): ControlFlowChain = ControlFlowChain(err)
