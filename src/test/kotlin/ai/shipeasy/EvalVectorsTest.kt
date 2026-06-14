package ai.shipeasy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-language eval-parity golden-vector test. The fixture
 * (`src/test/resources/eval-vectors.json`) is copied byte-identically from the
 * canonical source in `packages/core/src/eval/__fixtures__/eval-vectors.json`.
 * Every Shipeasy SDK that reimplements bucketing MUST reproduce every vector so
 * bucketing can never silently drift from the platform's canonical impl.
 *
 * Vectors are loaded from the resource at runtime — never hardcoded here.
 */
class EvalVectorsTest {
    private val fixture: JsonObject by lazy {
        val text = this::class.java.getResourceAsStream("/eval-vectors.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("eval-vectors.json missing from test resources")
        Json.parseToJsonElement(text).asObject()
    }

    private val bucketModulo: Int by lazy {
        (fixture["bucketModulo"] as JsonPrimitive).content.toInt()
    }

    /** (a) hash32 reproduces every hash vector, compared as unsigned 32-bit. */
    @Test fun hashVectors() {
        val vectors = (fixture["hash"] as JsonArray)
        assertTrue(vectors.isNotEmpty(), "no hash vectors")
        for (v in vectors) {
            val o = v.asObject()
            val input = (o["input"] as JsonPrimitive).content
            val expected = (o["hash"] as JsonPrimitive).content.toLong()
            val actual = Murmur3.hash32(input).toLong() and 0xffffffffL
            assertEquals(expected, actual, "hash32(${quote(input)}) unsigned 32-bit mismatch")
        }
        // Sanity: the modulo the fixture buckets against matches this SDK's.
        assertEquals(10000, bucketModulo, "bucketModulo drift")
    }

    /** (b) gate eval matches every `pass`. */
    @Test fun gateVectors() {
        val vectors = (fixture["gate"] as JsonArray)
        assertTrue(vectors.isNotEmpty(), "no gate vectors")
        for (v in vectors) {
            val o = v.asObject()
            val note = (o["note"] as? JsonPrimitive)?.content ?: ""
            val gate = o["gate"]!!.toAny() as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val user = o["user"]!!.toAny() as Map<String, Any?>
            val expected = (o["pass"] as JsonPrimitive).content.toBoolean()
            val actual = Eval.evalGate(gate, user)
            assertEquals(expected, actual, "gate vector failed: $note")
        }
    }

    /**
     * (c) experiment eval returns an assignment iff `result.inExperiment`, with
     * the assigned group equal to `result.group`.
     *
     * The fixture expresses targeting-gate state as a flat `flags` map of
     * `{gateName: bool}` and the universe holdout as a bare `holdoutRange`
     * `[lo,hi]`. The SDK's `evalExperiment` instead reads the targeting gate out
     * of `flags["gates"][name]` (re-evaluating it via `evalGate`) and the holdout
     * out of `exps["universes"][universe]["holdout_range"]`. We rebuild those
     * nested shapes from the flat fixture fields so we exercise the real
     * `evalExperiment` contract rather than a parallel reimplementation.
     */
    @Test fun experimentVectors() {
        val vectors = (fixture["experiment"] as JsonArray)
        assertTrue(vectors.isNotEmpty(), "no experiment vectors")
        for (v in vectors) {
            val o = v.asObject()
            val note = (o["note"] as? JsonPrimitive)?.content ?: ""

            @Suppress("UNCHECKED_CAST")
            val exp = o["experiment"]!!.toAny() as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val user = o["user"]!!.toAny() as Map<String, Any?>

            // {gateName: bool} → flags["gates"][gateName] = a gate map that
            // evalGate resolves to that bool. false → disabled gate; true → a
            // fully-rolled, ruleless gate (all targeting-true fixture cases carry
            // an identity, so 100% rollout always passes there).
            @Suppress("UNCHECKED_CAST")
            val flatFlags = (o["flags"]?.toAny() as? Map<String, Any?>) ?: emptyMap()
            val gates = flatFlags.mapValues { (_, on) ->
                if (on == true) {
                    mapOf("enabled" to true, "rolloutPct" to 10000, "salt" to "")
                } else {
                    mapOf("enabled" to false)
                }
            }
            val flags = mapOf("gates" to gates)

            // holdoutRange [lo,hi] → exps["universes"][universe]["holdout_range"].
            val universeName = exp["universe"] as? String
            val holdoutEl = o["holdoutRange"]
            val exps: Map<String, Any?> =
                if (holdoutEl != null && holdoutEl !is JsonNull && universeName != null) {
                    val range = (holdoutEl.toAny() as List<*>).map { (it as Number).toInt() }
                    mapOf(
                        "universes" to mapOf(
                            universeName to mapOf("holdout_range" to range),
                        ),
                    )
                } else {
                    emptyMap()
                }

            val result = Eval.evalExperiment(exp, flags, exps, user)

            val expected = o["result"].asObject()
            val expectedIn = (expected["inExperiment"] as JsonPrimitive).content.toBoolean()
            val expectedGroup = (expected["group"] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.contentOrNull()

            assertEquals(expectedIn, result.inExperiment, "experiment inExperiment mismatch: $note")
            if (expectedIn) {
                assertNotNull(expectedGroup, "fixture says inExperiment but no group: $note")
                assertEquals(expectedGroup, result.group, "experiment group mismatch: $note")
            }
        }
    }

    // --- JsonElement → plain Kotlin Any? helpers ----------------------------

    private fun JsonElement?.asObject(): JsonObject =
        this as? JsonObject ?: error("expected JSON object, got $this")

    private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content

    /** Convert a JsonElement into the plain Map/List/scalar shapes Eval expects. */
    private fun JsonElement.toAny(): Any? = when (this) {
        is JsonNull -> null
        is JsonObject -> entries.associate { (k, v) -> k to v.toAny() }
        is JsonArray -> map { it.toAny() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            intOrNull != null -> intOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }

    private fun quote(s: String) = "\"$s\""
}
