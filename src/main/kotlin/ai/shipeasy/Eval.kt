package ai.shipeasy

data class ExperimentResult(val inExperiment: Boolean, val group: String, val params: Any?)

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

    @Suppress("UNCHECKED_CAST")
    fun evalExperiment(
        exp: Map<String, Any?>?,
        flags: Map<String, Any?>?,
        exps: Map<String, Any?>?,
        user: Map<String, Any?>,
    ): ExperimentResult {
        if (exp == null || exp["status"] != "running") return NOT_IN

        (exp["targetingGate"] as? String)?.takeIf { it.isNotEmpty() }?.let { name ->
            val gates = flags?.get("gates") as? Map<String, Any?>
            val gate = gates?.get(name) as? Map<String, Any?>
            if (gate == null || !evalGate(gate, user)) return NOT_IN
        }

        // Bucket on exp.bucketBy (e.g. company_id) when set, else
        // user_id/anonymous_id. Holdout, allocation, and group all hash on this
        // SAME unit so a whole org moves together. No resolvable unit ⇒ not
        // enrolled. See packages/core/src/eval/experiment.ts.
        val bucketBy = exp["bucketBy"] as? String
        val uid = pickIdentifier(user, bucketBy) ?: return NOT_IN

        val universeName = exp["universe"] as? String
        if (universeName != null) {
            val universes = exps?.get("universes") as? Map<String, Any?>
            val universe = universes?.get(universeName) as? Map<String, Any?>
            val holdout = universe?.get("holdout_range") as? List<Number>
            if (holdout != null && holdout.size == 2) {
                val seg = Murmur3.bucket("$universeName:$uid", 10000)
                if (seg in holdout[0].toInt()..holdout[1].toInt()) return NOT_IN
            }
        }

        val salt = (exp["salt"] as? String) ?: ""
        val allocPct = (exp["allocationPct"] as? Number)?.toInt() ?: 0
        if (Murmur3.bucket("$salt:alloc:$uid", 10000) >= allocPct) return NOT_IN

        val groupHash = Murmur3.bucket("$salt:group:$uid", 10000)
        val groups = (exp["groups"] as? List<Map<String, Any?>>) ?: return NOT_IN
        var cumulative = 0
        groups.forEachIndexed { i, g ->
            cumulative += (g["weight"] as? Number)?.toInt() ?: 0
            if (groupHash < cumulative || i == groups.size - 1) {
                return ExperimentResult(true, g["name"] as? String ?: "control", g["params"])
            }
        }
        return NOT_IN
    }
}
