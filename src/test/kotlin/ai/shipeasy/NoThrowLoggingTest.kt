package ai.shipeasy

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fail-safe contract for the RUNTIME read path + the [LogLevel] gate:
 *  (a) a runtime read never throws into the caller, even against an adversarial
 *      blob — it returns the documented safe default;
 *  (b) LogLevel.SILENT mutes the leveled logger while WARN emits.
 */
class NoThrowLoggingTest {

    @AfterTest
    fun tearDown() {
        resetConfigureForTests()
        // Leave the shared logger back at the default verbosity for other suites.
        Log.setLevel(LogLevel.WARN)
    }

    // A blob whose "gates" entry has a structurally-broken `rules` list: the list
    // holds a String, so the canonical eval's per-rule cast throws at runtime.
    // A correct fail-safe read must swallow that and return the default.
    private fun adversarialFlagsBlob(): Map<String, Any?> = mapOf(
        "gates" to mapOf(
            "boom" to mapOf(
                "enabled" to true,
                "killswitch" to false,
                // Wrong element type — triggers a ClassCastException inside eval.
                "rules" to listOf("this is not a rule map"),
                "rolloutPct" to 10000,
            ),
        ),
        "configs" to emptyMap<String, Any?>(),
    )

    @Test
    fun runtimeReadReturnsSafeDefaultAndDoesNotThrow() {
        val engine = Engine.fromSnapshot(adversarialFlagsBlob(), emptyMap())
        installEngineForTests(engine)

        val client = Client(mapOf("user_id" to "u1"))

        // Adversarial gate: must NOT throw; must return the caller's default.
        assertFalse(client.getFlag("boom"), "getFlag must fall back to default false")
        assertTrue(client.getFlag("boom", default = true), "getFlag must return the supplied default")

        // Detail form: safe not-ready default, no throw.
        val detail = client.getFlagDetail("boom")
        assertEquals(Reason.CLIENT_NOT_READY, detail.reason)
        assertFalse(detail.value)

        // Direct engine reads for absent entities also stay safe.
        assertEquals("fallback", engine.getConfig("missing", "fallback"))
        assertFalse(engine.getKillswitch("missing"))
        // Universe-first read: an absent universe resolves to a not-enrolled
        // assignment with no defaults — never throws, and get() falls back.
        val a = client.universe("missing").assign()
        assertFalse(a.enrolled)
        assertEquals("fb", a.get("p", "fb"))

        // Fire-and-forget writes never throw.
        client.track("checkout")
    }

    @Test
    fun logLevelSilentMutesWhileWarnEmits() {
        val logger = Logger.getLogger("shipeasy")
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        logger.addHandler(handler)
        val prevUseParent = logger.useParentHandlers
        logger.useParentHandlers = false
        // Let every record reach our handler; Log's own gate decides emission.
        val prevLevel = logger.level
        logger.level = Level.ALL

        try {
            Log.setLevel(LogLevel.SILENT)
            captured.clear()
            Log.warn("should be muted")
            Log.error("should be muted too")
            assertTrue(captured.isEmpty(), "SILENT must emit nothing, got ${captured.size}")

            Log.setLevel(LogLevel.WARN)
            captured.clear()
            Log.warn("visible warn")
            Log.error("visible error")
            Log.info("info suppressed at WARN")
            Log.debug("debug suppressed at WARN")
            val messages = captured.map { it.message }
            assertTrue(messages.contains("visible warn"), "WARN must emit warn")
            assertTrue(messages.contains("visible error"), "WARN must emit error")
            assertFalse(messages.contains("info suppressed at WARN"), "WARN must suppress info")
            assertFalse(messages.contains("debug suppressed at WARN"), "WARN must suppress debug")
        } finally {
            logger.removeHandler(handler)
            logger.useParentHandlers = prevUseParent
            logger.level = prevLevel
        }
    }
}
