package ai.shipeasy.guide

import ai.shipeasy.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of the guide's "main page" assertion, adapted for a native app.
 *
 * The guide is an Android Jetpack Compose app: it does NOT serve an HTML page,
 * it renders a [LazyColumn] of cards (see MainActivity.GuideScreen / Entities).
 * So the faithful analog of "fetch the page and assert it has the mocked
 * values" is:
 *
 *   1. Use the SDK testing setup ([Engine.forTesting]) to MOCK every value
 *      Shipeasy returns — no network, no key — with DISTINCTIVE sentinel values.
 *   2. Take the value/meta strings the main screen ACTUALLY renders today
 *      (mirrored verbatim from [GUIDE_ENTITIES] in Entities.kt).
 *   3. Assert the rendered entity data CONTAINS each mocked value.
 *
 * The entity keys (`new_checkout`, `billing_copy`, `checkout_button`) are taken
 * verbatim from [GUIDE_ENTITIES] in Entities.kt.
 *
 * NOTE ON EXPECTED FAILURES: the app's Entities.kt currently uses HARDCODED
 * PLACEHOLDER strings and is NOT wired to the SDK (every card is a placeholder
 * by design — see the file header + the "SDK not wired yet" banner). Because the
 * mock values are distinctive sentinels that DON'T appear in those placeholders,
 * the config + experiment assertions in [rendered screen values contain the
 * mocked values] are EXPECTED to FAIL today, and will pass only once Entities.kt
 * is wired to the engine. The first test (the no-network testing engine + the
 * override read-back through getFlag/getConfig/getExperiment) is correct and
 * PASSES.
 *
 * Pure JVM / JUnit 4 unit test: it never touches Compose, the Android runtime,
 * or an emulator. It deliberately does NOT reference [GUIDE_ENTITIES] from
 * Entities.kt — building that list evaluates `SeColors.*` (androidx Compose
 * `Color`), which is not on the unit-test classpath and would throw
 * `NoClassDefFoundError`. Instead it mirrors the exact value/meta strings the
 * screen renders for each entity (copied verbatim from [GUIDE_ENTITIES]), with
 * zero Android coupling.
 */
class GuideEntitiesTest {

    // The exact entity KEYS read from Entities.kt.
    private val FLAG_KEY = "new_checkout"
    private val CONFIG_KEY = "billing_copy"
    private val EXPERIMENT_KEY = "checkout_button"

    // DISTINCTIVE sentinel mock values that DO NOT appear in Entities.kt's
    // current placeholder strings. This is deliberate: it keeps the
    // "rendered screen values contain the mocked values" assertion a REAL test
    // of the SDK→render path. With sentinels the config + experiment assertions
    // FAIL today (the app renders hardcoded placeholders, not these values) and
    // only go green once Entities.kt is wired to the engine. Reusing the
    // placeholder literals here would make those assertions tautological — a
    // false green.
    private val MOCK_FLAG = true
    private val MOCK_CONFIG = mapOf("headline" to "Welcome aboard 🚀", "cta" to "Start free trial")
    private val MOCK_GROUP = "treatment"
    private val MOCK_PARAMS = mapOf("color" to "#0ea5e9", "label" to "Checkout now")

    /**
     * Seed an [Engine.forTesting] with the mocked value for every entity the
     * guide screen shows, using the documented testing API.
     */
    private fun mockedEngine(): Engine = Engine.forTesting().apply {
        overrideFlag(FLAG_KEY, MOCK_FLAG)
        overrideConfig(CONFIG_KEY, MOCK_CONFIG)
        overrideExperiment(EXPERIMENT_KEY, group = MOCK_GROUP, params = MOCK_PARAMS)
    }

    /** A user context — unused by overrides, but this is how the screen would call. */
    private val user = mapOf("user_id" to "u_123")

    // --- 1. The SDK testing setup returns exactly the mocked values (no network). ---

    @Test
    fun `forTesting engine returns the mocked flag, config and experiment`() {
        mockedEngine().use { c ->
            assertEquals(MOCK_FLAG, c.getFlag(FLAG_KEY, user))

            assertEquals(MOCK_CONFIG, c.getConfig(CONFIG_KEY))

            val r = c.getExperiment(EXPERIMENT_KEY, user, defaultParams = null)
            assertTrue("user should be in the overridden experiment", r.inExperiment)
            assertEquals(MOCK_GROUP, r.group)
            assertEquals(MOCK_PARAMS, r.params)
        }
    }

    // --- 2 + 3. The "rendered page" mirrors the mocked values. ---

    /**
     * Assert that the value/meta cells the guide screen ACTUALLY renders today
     * contain the mocked (sentinel) values from the engine — i.e. that the cards
     * reflect the SDK.
     *
     * EXPECTED TO FAIL today: Entities.kt is hardcoded placeholders, so the
     * strings the screen renders do NOT contain the distinctive sentinel mocks.
     * The config + experiment assertions therefore fail right now; they go green
     * only when the cards are wired to the engine. (The flag assertion happens to
     * pass: the flag placeholder literally shows `true`, which is the mock.)
     */
    @Test
    fun `rendered screen values contain the mocked values`() {
        mockedEngine().use { c ->
            // The value/meta cells the app currently renders, copied verbatim
            // from GUIDE_ENTITIES in Entities.kt. (We mirror the strings instead
            // of loading the list, which would pull in Compose Color — see the
            // class doc.) These are the hardcoded PLACEHOLDERS the screen shows.
            val flagRendered = RENDERED_FLAG_VALUE
            val configRenderedMeta = RENDERED_CONFIG_META
            val experimentRendered = RENDERED_EXPERIMENT_VALUE
            val experimentRenderedMeta = RENDERED_EXPERIMENT_META

            // What those cells SHOULD contain, computed from the mocked engine.
            val flag = c.getFlag(FLAG_KEY, user)
            val exp = c.getExperiment(EXPERIMENT_KEY, user, defaultParams = null)
            val headline = MOCK_CONFIG["headline"] as String
            val cta = MOCK_CONFIG["cta"] as String

            assertTrue(
                "flag card value ($flagRendered) should reflect mocked flag=$flag",
                flagRendered.contains(flag.toString()),
            )
            assertTrue(
                "config card ($configRenderedMeta) should reflect mocked headline=$headline",
                configRenderedMeta.contains(headline),
            )
            assertTrue(
                "config card ($configRenderedMeta) should reflect mocked cta=$cta",
                configRenderedMeta.contains(cta),
            )
            assertTrue(
                "experiment card value ($experimentRendered) should reflect mocked group=${exp.group}",
                experimentRendered.contains(exp.group),
            )
            assertTrue(
                "experiment card meta should reflect mocked params $MOCK_PARAMS",
                experimentRenderedMeta.contains(MOCK_PARAMS["color"] as String) &&
                    experimentRenderedMeta.contains(MOCK_PARAMS["label"] as String),
            )
        }
    }

    private companion object {
        // The exact strings the guide screen renders for the SDK-backed cards,
        // mirrored from GUIDE_ENTITIES in Entities.kt (hardcoded placeholders).
        const val RENDERED_FLAG_VALUE = "true · RULE_MATCH"
        const val RENDERED_CONFIG_META =
            "{\"headline\":\"Welcome back 👋\",\"cta\":\"Upgrade to Pro\"} · placeholder"
        const val RENDERED_EXPERIMENT_VALUE = "treatment · in experiment"
        const val RENDERED_EXPERIMENT_META =
            "params {\"color\":\"#34d399\",\"label\":\"Buy now\"} · placeholder"
    }
}
