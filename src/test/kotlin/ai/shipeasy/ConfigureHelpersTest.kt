package ai.shipeasy

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Doc-23 configure() family + package-level helpers, all read through the bound
 * [Client] (never the [Engine]).
 */
class ConfigureHelpersTest {
    @BeforeTest
    fun setup() {
        resetConfigureForTests()
    }

    @AfterTest
    fun teardown() {
        resetConfigureForTests()
    }

    @Test
    fun configureForTestingSeedsAndReplaces() {
        configureForTesting(
            flags = mapOf("new_checkout" to true),
            configs = mapOf("theme" to "blue"),
            experiments = mapOf("price_test" to ("treatment" to mapOf("price" to 9))),
        )
        val c = Client(mapOf("user_id" to "u_1"))
        assertTrue(c.getFlag("new_checkout"))
        assertEquals("blue", c.getConfig("theme"))
        val r = c.getExperiment("price_test", null)
        assertTrue(r.inExperiment)
        assertEquals("treatment", r.group)

        // REPLACE (not first-wins): a second call wins.
        configureForTesting(flags = mapOf("new_checkout" to false))
        assertFalse(Client(emptyMap<String, Any?>()).getFlag("new_checkout"))
    }

    @Test
    fun packageOverridesAndClear() {
        configureForTesting(flags = mapOf("f" to true))
        overrideFlag("f", false)
        overrideConfig("c", 123)
        overrideExperiment("e", "B", mapOf("v" to 2))
        val c = Client(mapOf("user_id" to "u"))
        assertFalse(c.getFlag("f"))
        assertEquals(123, c.getConfig("c"))
        assertEquals("B", c.getExperiment("e", null).group)

        // Test mode has no blob beneath: clearOverrides drops the seed too.
        clearOverrides()
        assertNull(Client(emptyMap<String, Any?>()).getConfig("c"))
    }

    @Test
    fun overrideBeforeConfigureThrows() {
        assertFailsWith<IllegalStateException> { overrideFlag("f", true) }
    }

    @Test
    fun configureForOfflineLayersOverrides() {
        val snapshot = mapOf<String, Any?>(
            "flags" to mapOf(
                "gates" to mapOf("on_for_all" to mapOf("enabled" to true, "rolloutPct" to 10000, "salt" to "s")),
                "configs" to mapOf("color" to mapOf("value" to "green")),
                "killswitches" to emptyMap<String, Any?>(),
            ),
            "experiments" to mapOf("experiments" to emptyMap<String, Any?>(), "universes" to emptyMap<String, Any?>()),
        )
        configureForOffline(snapshot = snapshot)
        assertTrue(Client(mapOf("user_id" to "u_1")).getFlag("on_for_all"))
        assertEquals("green", Client(emptyMap<String, Any?>()).getConfig("color"))

        overrideFlag("on_for_all", false)
        assertFalse(Client(emptyMap<String, Any?>()).getFlag("on_for_all"))
        clearOverrides()
        assertTrue(Client(emptyMap<String, Any?>()).getFlag("on_for_all"))
    }

    @Test
    fun configureForOfflineRequiresSource() {
        assertFailsWith<IllegalArgumentException> { configureForOffline() }
    }
}
