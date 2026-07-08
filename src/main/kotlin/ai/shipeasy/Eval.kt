package ai.shipeasy

data class ExperimentResult(val inExperiment: Boolean, val group: String, val params: Any?)

/**
 * The result of `universe(name).assign(...)` — a unit's standing in a universe.
 * A universe is a mutual-exclusion pool, so a unit lands in **at most one**
 * experiment. Never throws: an un-enrolled unit still resolves [get] to the
 * universe defaults (or your fallback). Reading is side-effect free — the single
 * exposure is auto-logged by `assign()` when the unit is enrolled. Mirrors the
 * canonical TS `Assignment` (doc 20 §B).
 */
class Assignment internal constructor(
    /** The experiment the unit landed in, or `null` when not enrolled. */
    val name: String?,
    /** The assigned variant/group name, or `null` when not enrolled. */
    val group: String?,
    // Already merged (universeDefaults ⊕ variantOverride) when enrolled;
    // defaults-only (or empty) when not.
    private val params: Map<String, Any?>,
) {
    /** True iff the unit is enrolled in an experiment in this universe. */
    val enrolled: Boolean get() = group != null

    /**
     * Read a resolved param: the assigned variant's override, else the universe
     * default, else [fallback]. Works even when not enrolled (variant layer is
     * absent, so you get `universeDefault ?? fallback`). Never throws.
     */
    @JvmOverloads
    fun get(field: String, fallback: Any? = null): Any? {
        val v = params[field]
        return if (v == null && !params.containsKey(field)) fallback else v
    }
}

/**
 * One persisted sticky assignment: the chosen group plus the 8-char salt prefix
 * (the reshuffle key). Matches the canonical TS `StickyEntry { g, s }` (doc 20
 * §2) — `salt8` is `experiment.salt.substring(0, 8)`.
 */
data class StickyEntry(val group: String, val salt8: String)

/**
 * Pluggable sticky-bucketing store for the server (doc 20 §2). Keyed by the
 * bucketing unit ([Eval.pickIdentifier]-resolved); the value is that unit's
 * per-experiment assignments (experiment name → [StickyEntry]). Absent from the
 * [Engine] ⇒ today's deterministic behaviour. Built-in: [InMemoryStickyStore].
 */
interface StickyBucketStore {
    /** All assignments for [unit] (experiment name → entry), or null if none. */
    fun get(unit: String): Map<String, StickyEntry>?

    /** Persist [entry] for ([unit], [exp]). */
    fun set(unit: String, exp: String, entry: StickyEntry)
}

/**
 * A process-local sticky store (thread-safe Map-backed). Handy for tests and
 * single-process servers. Mirrors the TS `createInMemoryStickyStore`.
 */
class InMemoryStickyStore(
    seed: Map<String, Map<String, StickyEntry>> = emptyMap(),
) : StickyBucketStore {
    private val m = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, StickyEntry>>()

    init {
        for ((unit, entries) in seed) m[unit] = java.util.concurrent.ConcurrentHashMap(entries)
    }

    override fun get(unit: String): Map<String, StickyEntry>? = m[unit]

    override fun set(unit: String, exp: String, entry: StickyEntry) {
        m.computeIfAbsent(unit) { java.util.concurrent.ConcurrentHashMap() }[exp] = entry
    }
}

/**
 * A unit's standing in one experiment: an assigned [group] (with already-merged
 * [params]), `holdout` (universe carve-out or holdout gate — never assigned), or
 * `out`. Internal — the public surface is [Assignment]. Mirrors the TS
 * `ExpStanding`.
 */
internal class ExpStanding private constructor(
    val state: State,
    val group: String? = null,
    val params: Map<String, Any?>? = null,
) {
    enum class State { GROUP, HOLDOUT, OUT }

    companion object {
        val OUT = ExpStanding(State.OUT)
        val HOLDOUT = ExpStanding(State.HOLDOUT)
        fun group(name: String, params: Map<String, Any?>) = ExpStanding(State.GROUP, name, params)
    }
}

internal object Eval {
    val NOT_IN = ExperimentResult(false, "control", null)

    private fun enabled(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() == 1
        else -> false
    }

    private fun toNum(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun userId(user: Map<String, Any?>): String? =
        (user["user_id"] ?: user["anonymous_id"])?.toString()

    /**
     * Resolve the bucketing identifier, matching the canonical
     * `pickIdentifier` in `packages/core/src/eval/gate.ts`. When [bucketBy] is
     * set and the user carries a non-empty String at that attribute, bucket on
     * it (e.g. `company_id` to keep a whole org on one variant); a Number is
     * stringified. Otherwise fall back to `user_id` ?? `anonymous_id`.
     */
    private fun pickIdentifier(user: Map<String, Any?>, bucketBy: String?): String? {
        if (!bucketBy.isNullOrEmpty()) {
            when (val v = user[bucketBy]) {
                is String -> if (v.isNotEmpty()) return v
                is Number -> return v.toString()
                else -> {}
            }
        }
        return userId(user)
    }

    /**
     * Flatten a universe param schema (`[{ name, type, default }]`) to a plain
     * `name → default` map — the defaults `assign()` layers under a variant's
     * override map (§B2). Returns null for a null/empty schema so the merge
     * short-circuits. Mirrors the canonical `paramDefaultsFromSchema`.
     */
    @Suppress("UNCHECKED_CAST")
    fun paramDefaultsFromSchema(schema: Any?): Map<String, Any?>? {
        val list = schema as? List<Map<String, Any?>> ?: return null
        if (list.isEmpty()) return null
        val out = LinkedHashMap<String, Any?>()
        for (p in list) (p["name"] as? String)?.let { out[it] = p["default"] }
        return out
    }

    /**
     * `universeDefaults ⊕ variantOverride` — a variant inherits every universe
     * default it doesn't explicitly override (§B2).
     */
    fun mergeParams(paramDefaults: Map<String, Any?>?, groupParams: Map<String, Any?>): Map<String, Any?> =
        if (paramDefaults != null) paramDefaults + groupParams else LinkedHashMap(groupParams)

    @Suppress("UNCHECKED_CAST")
    private fun matchRule(rule: Map<String, Any?>, user: Map<String, Any?>): Boolean {
        val attr = rule["attr"] as? String ?: return false
        val op = rule["op"] as? String ?: return false
        val value = rule["value"]
        val actual = user[attr]
        return when (op) {
            "eq" -> actual == value
            "neq" -> actual != value
            "in" -> (value as? List<Any?>)?.contains(actual) ?: false
            "not_in" -> (value as? List<Any?>)?.contains(actual) != true
            "contains" -> when {
                actual is String && value is String -> actual.contains(value)
                actual is List<*> -> actual.contains(value)
                else -> false
            }
            "regex" -> if (actual is String && value is String) runCatching { Regex(value).containsMatchIn(actual) }.getOrDefault(false) else false
            "gt", "gte", "lt", "lte" -> {
                val a = toNum(actual); val b = toNum(value)
                if (a == null || b == null) false
                else when (op) { "gt" -> a > b; "gte" -> a >= b; "lt" -> a < b; else -> a <= b }
            }
            else -> false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun evalGate(gate: Map<String, Any?>?, user: Map<String, Any?>): Boolean {
        if (gate == null) return false
        if (enabled(gate["killswitch"])) return false
        if (!enabled(gate["enabled"])) return false
        (gate["rules"] as? List<Map<String, Any?>>)?.forEach { if (!matchRule(it, user)) return false }
        val rolloutPct = (gate["rolloutPct"] as? Number)?.toInt() ?: 0
        // No unit id (an unidentified request before any anon id is minted): a
        // fully-rolled gate is on for everyone, so it can be answered without
        // bucketing; a fractional rollout needs a stable unit, so deny until one
        // exists. Rules above still apply, so targeting wins.
        // See experiment-platform/18-identity-bucketing.md.
        val uid = userId(user) ?: return rolloutPct >= 10000
        val salt = (gate["salt"] as? String) ?: ""
        return Murmur3.bucket("$salt:$uid", 10000) < rolloutPct
    }

    /**
     * Backwards-compatible thin wrapper: run [classifyExperiment] and project the
     * standing onto the legacy [ExperimentResult]. Still keyed by experiment name
     * and used by the SSR `evaluate()` bootstrap and the universe assignment path.
     * Returns the merged params (universeDefaults ⊕ variant) when enrolled.
     */
    fun evalExperiment(
        exp: Map<String, Any?>?,
        flags: Map<String, Any?>?,
        exps: Map<String, Any?>?,
        user: Map<String, Any?>,
        expName: String? = null,
        store: StickyBucketStore? = null,
    ): ExperimentResult {
        val c = classifyExperiment(exp, flags, exps, user, expName, store)
        return if (c.state == ExpStanding.State.GROUP) {
            ExperimentResult(true, c.group ?: "control", c.params)
        } else {
            NOT_IN
        }
    }

    /**
     * Targeting → universe holdout → holdout gate → sticky → allocation (pooled
     * or legacy) → weighted group split. The single local mirror of
     * @shipeasy/core's `classifyExperiment` (see the TS `classifyExperimentLocal`).
     * Universe param defaults are merged UNDER an assigned variant's overrides so
     * an unset param still returns the universe default (§B2).
     *
     * When [store] is non-null and it holds an assignment for (unit, expName)
     * whose salt8 still matches, the stored group is returned without re-running
     * allocation/pick; a fresh pick is persisted. Both null ⇒ deterministic.
     */
    @Suppress("UNCHECKED_CAST")
    fun classifyExperiment(
        exp: Map<String, Any?>?,
        flags: Map<String, Any?>?,
        exps: Map<String, Any?>?,
        user: Map<String, Any?>,
        expName: String? = null,
        store: StickyBucketStore? = null,
    ): ExpStanding {
        if (exp == null || exp["status"] != "running") return ExpStanding.OUT

        val universeName = exp["universe"] as? String
        val universe = (exps?.get("universes") as? Map<String, Any?>)?.get(universeName) as? Map<String, Any?>
        val paramDefaults = paramDefaultsFromSchema(universe?.get("param_schema"))

        val evalGateByName: (String) -> Boolean = { name ->
            val gate = (flags?.get("gates") as? Map<String, Any?>)?.get(name) as? Map<String, Any?>
            if (gate != null) evalGate(gate, user) else false
        }

        (exp["targetingGate"] as? String)?.takeIf { it.isNotEmpty() }?.let { name ->
            if (!evalGateByName(name)) return ExpStanding.OUT
        }

        // Bucket on exp.bucketBy (e.g. company_id) when set, else
        // user_id/anonymous_id. Holdout, allocation, and group all hash on this
        // SAME unit so a whole org moves together. No resolvable unit ⇒ not
        // enrolled. See packages/core/src/eval/experiment.ts.
        val bucketBy = exp["bucketBy"] as? String
        val uid = pickIdentifier(user, bucketBy) ?: return ExpStanding.OUT

        // One segment in the universe's shared `[0, 10000)` hash space. The holdout
        // carve-out AND every experiment's pool slice are disjoint ranges of THIS
        // segment — that's what makes "held out / taken / free" a real partition.
        val universeSeg = if (universeName != null) Murmur3.bucket("$universeName:$uid", 10000) else -1

        if (universeName != null) {
            val holdout = universe?.get("holdout_range") as? List<Number>
            if (holdout != null && holdout.size == 2 &&
                universeSeg >= holdout[0].toInt() && universeSeg <= holdout[1].toInt()
            ) {
                return ExpStanding.HOLDOUT
            }
        }

        // Per-experiment holdout gate: a passing gate holds the unit out of THIS
        // experiment (never assigned, sees universe defaults). §B3.
        (exp["holdoutGate"] as? String)?.takeIf { it.isNotEmpty() }?.let { name ->
            if (evalGateByName(name)) return ExpStanding.HOLDOUT
        }

        val salt = (exp["salt"] as? String) ?: ""
        val salt8 = salt.take(8)
        val groups = (exp["groups"] as? List<Map<String, Any?>>) ?: return ExpStanding.OUT

        fun asGroup(g: Map<String, Any?>): ExpStanding {
            val name = g["name"] as? String ?: "control"
            val gp = (g["params"] as? Map<String, Any?>) ?: emptyMap()
            return ExpStanding.group(name, mergeParams(paramDefaults, gp))
        }

        // Sticky short-circuit (doc 20 §2): an enrolled unit whose stored salt
        // prefix still matches skips the allocation gate (so a shrinking
        // allocation keeps it in) and returns the stored group without re-running
        // the pick. A salt mismatch or a vanished group falls through to
        // re-bucket + overwrite below.
        if (store != null && expName != null) {
            val entry = store.get(uid)?.get(expName)
            if (entry != null && entry.salt8 == salt8) {
                val g = groups.firstOrNull { (it["name"] as? String) == entry.group }
                if (g != null) return asGroup(g)
                // Stored group gone — fall through to re-bucket + overwrite.
            }
        }

        // Allocation. Pooled (hashVersion ≥ 2 with a slice) gives real mutual
        // exclusion: the unit's universe segment must fall in the claimed range.
        // Legacy falls back to an independent per-experiment salt so siblings
        // overlap freely. §B4.
        val hashVersion = (exp["hashVersion"] as? Number)?.toInt() ?: 1
        val poolOffset = (exp["poolOffsetBp"] as? Number)?.toInt()
        val poolSize = (exp["poolSizeBp"] as? Number)?.toInt()
        val pooled = hashVersion >= 2 && poolOffset != null && poolSize != null && poolSize > 0
        if (pooled) {
            val lo = poolOffset!!
            val hi = lo + poolSize!!
            if (universeSeg < lo || universeSeg >= hi) return ExpStanding.OUT
        } else {
            val allocPct = (exp["allocationPct"] as? Number)?.toInt() ?: 0
            if (Murmur3.bucket("$salt:alloc:$uid", 10000) >= allocPct) return ExpStanding.OUT
        }

        // Group split over `[0, usable)` where `usable = 10000 − reserved`; a unit
        // in the reserved tail is left unassigned so an appended variant can absorb
        // it (§B5).
        val reserved = ((exp["reservedHeadroomBp"] as? Number)?.toInt() ?: 0).coerceIn(0, 10000)
        val usable = 10000 - reserved
        val groupHash = Murmur3.bucket("$salt:group:$uid", 10000)
        if (groupHash >= usable) return ExpStanding.OUT
        var cumulative = 0
        groups.forEachIndexed { i, g ->
            cumulative += (g["weight"] as? Number)?.toInt() ?: 0
            if (groupHash < cumulative || i == groups.size - 1) {
                val name = g["name"] as? String ?: "control"
                if (store != null && expName != null) store.set(uid, expName, StickyEntry(name, salt8))
                return asGroup(g)
            }
        }
        return ExpStanding.OUT
    }
}
