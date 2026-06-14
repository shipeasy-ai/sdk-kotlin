package ai.shipeasy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnonIdTest {
    @Test fun mintIsValidUuid() {
        val id = AnonId.mint()
        assertTrue(AnonId.isValid(id))
        assertEquals(36, id.length)
        assertNotEquals(AnonId.mint(), AnonId.mint())
    }

    @Test fun rejectsTampered() {
        assertFalse(AnonId.isValid("bad value!"))
        assertFalse(AnonId.isValid(null))
        assertFalse(AnonId.isValid(""))
    }

    @Test fun buildSetCookieContract() {
        val h = AnonId.buildSetCookie("abc", true)
        assertTrue(h.contains("__se_anon_id=abc"))
        assertTrue(h.contains("Path=/"))
        assertTrue(h.contains("Max-Age=31536000"))
        assertTrue(h.contains("SameSite=Lax"))
        assertTrue(h.contains("Secure"))
        assertFalse(h.contains("HttpOnly"))
        assertFalse(AnonId.buildSetCookie("abc", false).contains("Secure"))
    }

    @Test fun threadLocalCurrent() {
        assertNull(AnonId.current())
        AnonId.setCurrent("anon-1")
        assertEquals("anon-1", AnonId.current())
        AnonId.clear()
        assertNull(AnonId.current())
    }

    // The no-unit eval rule is a cross-SDK contract.
    @Test fun evalGateNoUnit() {
        assertTrue(Eval.evalGate(mapOf("enabled" to 1, "salt" to "s", "rolloutPct" to 10000), emptyMap()))
        assertFalse(Eval.evalGate(mapOf("enabled" to 1, "salt" to "s", "rolloutPct" to 5000), emptyMap()))
        assertFalse(Eval.evalGate(mapOf("enabled" to 0, "rolloutPct" to 10000), emptyMap()))
        assertFalse(Eval.evalGate(mapOf("enabled" to 1, "killswitch" to 1, "rolloutPct" to 10000), emptyMap()))

        val ruled = mapOf(
            "enabled" to 1, "salt" to "s", "rolloutPct" to 10000,
            "rules" to listOf(mapOf("attr" to "plan", "op" to "eq", "value" to "pro")),
        )
        assertFalse(Eval.evalGate(ruled, emptyMap()))
        assertTrue(Eval.evalGate(ruled, mapOf("plan" to "pro")))

        assertFalse(Eval.evalGate(mapOf("enabled" to 1, "salt" to "s", "rolloutPct" to 0), mapOf("user_id" to "u1")))
    }
}
