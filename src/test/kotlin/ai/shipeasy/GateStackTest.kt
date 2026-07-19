package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gatekeeper `stack` evaluation regression guard for the bug where [Eval.evalGate]
 * read only the flat `rules`+`rolloutPct` and ignored a modern gate's ordered
 * `stack`. The canonical model is the stack (mirrors @shipeasy/core
 * evalGatekeeper + the edge worker); the flat columns are a lossy approximation
 * that can invert the result — a whitelist condition at 100% followed by a 0%
 * public rollout flattens to `rolloutPct: 0`, which the flat path would wrongly
 * read as "matches whitelist AND in the 0% bucket" = never. These vectors lock
 * the SDK to the stack. Mirrors sdk-ts `gate-stack.test.ts`.
 */
class GateStackTest {
    private val P = "e976b15e-3ccc-44d3-821d-87f06d5a0e43"

    // The exact shape the KV rebuild ships for a whitelist gatekeeper: a condition
    // (no explicit rolloutPct ⇒ 100%) that whitelists a project, then a locked 0%
    // public rollout. The flat columns are the lossy approximation.
    private fun whitelistGate(): Map<String, Any?> = mapOf(
        "name" to "release_module",
        "enabled" to 1,
        "salt" to "caf3a1ae",
        // Lossy flat approximation — must NOT be what decides the result.
        "rules" to listOf(mapOf("attr" to "project_id", "op" to "in", "value" to listOf(P))),
        "rolloutPct" to 0,
        "stack" to listOf(
            mapOf(
                "id" to "gq578snc",
                "type" to "condition",
                "pass" to "all",
                "rules" to listOf(mapOf("attr" to "project_id", "op" to "in", "value" to listOf(P))),
            ),
            mapOf("id" to "gu0uein4", "type" to "rollout", "rolloutPct" to 0, "bucketBy" to "user_id", "salt" to "public"),
        ),
    )

    @Test fun whitelistedCallerPassesEvenThoughFlatRolloutIsZero() {
        // The regression: the flat path reads "matches whitelist AND 0% bucket" =
        // false. The stack short-circuits on the 100% condition → true.
        assertTrue(Eval.evalGate(whitelistGate(), mapOf("user_id" to "cdewqzx@gmail.com", "project_id" to P)))
    }

    @Test fun nonWhitelistedCallerHidden() {
        // Condition misses, the public rollout is 0% → false.
        assertFalse(Eval.evalGate(whitelistGate(), mapOf("user_id" to "someone@else.com", "project_id" to "other-project")))
    }

    @Test fun whitelistedCallerWithNoIdentityPasses() {
        // No user_id/anonymous_id: a fully-rolled (100%) condition is answerable
        // without a unit id.
        assertTrue(Eval.evalGate(whitelistGate(), mapOf("project_id" to P)))
    }

    @Test fun matchingConditionStillGatesOnItsOwnRollout() {
        val gate = mapOf(
            "name" to "g",
            "enabled" to 1,
            "salt" to "s",
            "rules" to emptyList<Any?>(),
            "rolloutPct" to 0,
            "stack" to listOf(
                mapOf(
                    "id" to "c1",
                    "type" to "condition",
                    "pass" to "all",
                    "rules" to listOf(mapOf("attr" to "project_id", "op" to "in", "value" to listOf(P))),
                    "rolloutPct" to 0, // matched but 0% → never
                ),
            ),
        )
        assertFalse(Eval.evalGate(gate, mapOf("user_id" to "u1", "project_id" to P)))
    }

    @Test fun supportsPassAnyConditions() {
        val gate = mapOf(
            "name" to "g",
            "enabled" to 1,
            "salt" to "s",
            "rules" to emptyList<Any?>(),
            "rolloutPct" to 0,
            "stack" to listOf(
                mapOf(
                    "id" to "c1",
                    "type" to "condition",
                    "pass" to "any",
                    "rules" to listOf(
                        mapOf("attr" to "plan", "op" to "eq", "value" to "pro"),
                        mapOf("attr" to "project_id", "op" to "in", "value" to listOf(P)),
                    ),
                ),
            ),
        )
        // plan misses but project_id matches → one branch passes → true.
        assertTrue(Eval.evalGate(gate, mapOf("user_id" to "u", "plan" to "free", "project_id" to P)))
        // neither branch matches → false.
        assertFalse(Eval.evalGate(gate, mapOf("user_id" to "u", "plan" to "free", "project_id" to "x")))
    }

    @Test fun fallsThroughToLaterRolloutCatchAll() {
        val gate = mapOf(
            "name" to "g",
            "enabled" to 1,
            "salt" to "s",
            "rules" to emptyList<Any?>(),
            "rolloutPct" to 0,
            "stack" to listOf(
                mapOf(
                    "id" to "c1",
                    "type" to "condition",
                    "pass" to "all",
                    "rules" to listOf(mapOf("attr" to "project_id", "op" to "in", "value" to listOf(P))),
                ),
                mapOf("id" to "public", "type" to "rollout", "rolloutPct" to 10000), // everyone else: 100%
            ),
        )
        assertTrue(Eval.evalGate(gate, mapOf("user_id" to "u", "project_id" to "not-whitelisted")))
    }

    @Test fun disabledOrKilledStackedGateIsOff() {
        assertFalse(Eval.evalGate(whitelistGate() + ("enabled" to 0), mapOf("user_id" to "u", "project_id" to P)))
        assertFalse(Eval.evalGate(whitelistGate() + ("killswitch" to 1), mapOf("user_id" to "u", "project_id" to P)))
    }

    @Test fun stackLessGateStillUsesLegacyFlatPath() {
        val on = mapOf("name" to "on", "enabled" to 1, "salt" to "s", "rules" to emptyList<Any?>(), "rolloutPct" to 10000)
        val off = mapOf("name" to "off", "enabled" to 1, "salt" to "s", "rules" to emptyList<Any?>(), "rolloutPct" to 0)
        assertTrue(Eval.evalGate(on, mapOf("user_id" to "u")))
        assertFalse(Eval.evalGate(off, mapOf("user_id" to "u")))
    }
}
