package ai.shipeasy

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// shipeasy ops #714: the signed DevTools override cookie (`se_ov`) reaches
// server-side reads. Vectors are the cross-SDK fixture (se_ov_fixture.json).
class RequestOverridesTest {
    private val key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
    private val keyId = "k1"
    private val cookie = "eyJ2IjoxLCJwaWQiOiJwcm9qX2ZpeHR1cmUiLCJrZXlJZCI6ImsxIiwibm9uY2UiOiJmaXh0dXJlLW5vbmNlIiwiZXhwIjo0MTAyNDQ0ODAwLCJvdiI6eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX19.8qd-pHu1e96Th-jcu4BZOhLmZjwhKrgn-uI8IWBr5Og"
    private val expired = "eyJ2IjoxLCJwaWQiOiJwcm9qX2ZpeHR1cmUiLCJrZXlJZCI6ImsxIiwibm9uY2UiOiJmaXh0dXJlLW5vbmNlIiwiZXhwIjoxMDAwLCJvdiI6eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX19.fZEp7eGjZSdi5EvTB-5NZLbZpVXbcWbIaEYRmPvPbd8"
    private val tampered = "eyJ2IjoxLCJwaWQiOiJwcm9qX2ZpeHR1cmUiLCJrZXlJZCI6ImsxIiwibm9uY2UiOiJmaXh0dXJlLW5vbmNlIiwiZXhwIjo0MTAyNDQ0ODAwLCJvdiI6eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX19.8qd-pHu1e96Th-jcu4BZOhLmZjwhKrgn-uI8IWBr5AA"

    @BeforeTest @AfterTest
    fun reset() {
        resetConfigureForTests()
        setDefaultClient(null)
        AnonId.clear()
        SeeContext.clear()
    }

    private fun flagsBlob(withKey: Boolean = true): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "gates" to mapOf(
                "lobby_hold" to mapOf("enabled" to true, "rolloutPct" to 0),
                "old_nav" to mapOf("enabled" to true, "rolloutPct" to 10000),
                "plain" to mapOf("enabled" to true, "rolloutPct" to 10000),
            ),
            "configs" to mapOf("banner" to mapOf("value" to mapOf("text" to "orig"))),
            "killswitches" to mapOf("lobby_hold" to mapOf("killed" to false)),
        )
        if (withKey) { m["overrideKey"] = key; m["overrideKeyId"] = keyId }
        return m
    }

    private val exps: Map<String, Any?> = mapOf(
        "universes" to mapOf("u1" to mapOf("param_schema" to mapOf("color" to mapOf("default" to "blue")))),
        "experiments" to mapOf(
            "checkout_exp" to mapOf(
                "universe" to "u1", "status" to "running", "salt" to "s", "allocationPct" to 10000,
                "groups" to listOf(
                    mapOf("name" to "control", "weight" to 10000, "params" to mapOf<String, Any?>()),
                    mapOf("name" to "treatment", "weight" to 0, "params" to mapOf("color" to "green")),
                ),
            ),
        ),
    )

    private fun install(withKey: Boolean = true): Engine {
        val e = Engine.fromSnapshot(flagsBlob(withKey), exps)
        installEngineForTests(e)
        return e
    }

    private val user = mapOf("user_id" to "u1")

    // ── verifier ─────────────────────────────────────────────────────────────
    @Test fun fixtureVerifies() {
        val ov = OverrideCookie.verify(cookie, key, keyId)
        assertNotNull(ov)
        assertEquals(mapOf("lobby_hold" to true, "old_nav" to false), ov.gates)
        assertEquals(mapOf("banner" to mapOf("text" to "hi")), ov.configs)
        assertEquals(mapOf("checkout_exp" to "treatment"), ov.exps)
    }

    @Test fun rejectsExpiredTamperedWrongKeyIdWrongKeyAndMalformed() {
        assertNull(OverrideCookie.verify(expired, key, keyId))
        assertNull(OverrideCookie.verify(tampered, key, keyId))
        assertNull(OverrideCookie.verify(cookie, key, "k2"))
        assertNull(OverrideCookie.verify(cookie, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", keyId))
        assertNull(OverrideCookie.verify(cookie, "!!not base64!!", keyId))
        assertNull(OverrideCookie.verify("nodot", key, keyId))
        assertNull(OverrideCookie.verify(".abc", key, keyId))
        assertNull(OverrideCookie.verify("a.b.c", key, keyId))
        assertNull(OverrideCookie.verify("", key, keyId))
        // `exp` in the past relative to an injected clock.
        assertNull(OverrideCookie.verify(cookie, key, keyId, nowSeconds = 4_102_444_800L))
    }

    @Test fun extractsFromBareValueAndCookieHeader() {
        assertEquals(cookie, OverrideCookie.extract(cookie))
        assertEquals(cookie, OverrideCookie.extract("a=1; se_ov=$cookie; b=2"))
        assertEquals(cookie, OverrideCookie.extract("se_ov=" + cookie.replace(".", "%2E")))
        assertNull(OverrideCookie.extract("a=1; b=2"))
        assertNull(OverrideCookie.extract("nodots"))
        assertNull(OverrideCookie.extract(null))
        assertNull(OverrideCookie.extract(""))
    }

    // ── scope + read path ────────────────────────────────────────────────────
    @Test fun overridesApplyInsideScopeAndNotOutside() {
        install()
        val flags = Client(user)
        assertFalse(flags.getFlag("lobby_hold"))
        assertTrue(flags.getFlag("old_nav"))
        withRequestOverrides("a=1; se_ov=$cookie") {
            val d = Client(user).getFlagDetail("lobby_hold")
            assertTrue(d.value)
            assertEquals(Reason.OVERRIDE, d.reason)
            assertFalse(Client(user).getFlag("old_nav"))
            assertTrue(Client(user).getFlag("plain"))          // untouched
            assertTrue(Client(user).getKillswitch("lobby_hold")) // gates cover kill switches
            assertEquals(mapOf("text" to "hi"), Client(user).getConfig("banner"))
            assertEquals("treatment", Client(user).universe("u1").assign().group)
            assertEquals("green", Client(user).universe("u1").assign().get("color", "x"))
        }
        assertNull(RequestOverrides.current())
        assertFalse(flags.getFlag("lobby_hold"))
        assertTrue(flags.getFlag("old_nav"))
        assertFalse(Client(user).getKillswitch("lobby_hold"))
        assertEquals(mapOf("text" to "orig"), Client(user).getConfig("banner"))
        assertEquals("control", Client(user).universe("u1").assign().group)
    }

    @Test fun killswitchSubKeysAreLeftAlone() {
        install()
        withRequestOverrides(cookie) {
            assertFalse(Client(user).getKillswitch("lobby_hold", "some_switch"))
        }
    }

    @Test fun programmaticOverrideWinsOverCookie() {
        val e = install()
        e.overrideFlag("lobby_hold", false)
        e.overrideConfig("banner", "prog")
        e.overrideExperiment("checkout_exp", "control", null)
        withRequestOverrides(cookie) {
            assertFalse(Client(user).getFlag("lobby_hold"))
            assertEquals("prog", Client(user).getConfig("banner"))
            assertEquals("control", Client(user).universe("u1").assign().group)
        }
    }

    @Test fun noKeyServedMeansFailClosed() {
        install(withKey = false)
        withRequestOverrides(cookie) {
            assertFalse(Client(user).getFlag("lobby_hold"))
            assertNull(RequestOverrides.current())
        }
    }

    @Test fun badCookiesAreIgnoredInsideScope() {
        install()
        for (c in listOf(expired, tampered, "garbage", null)) {
            withRequestOverrides(c) { assertFalse(Client(user).getFlag("lobby_hold")) }
        }
    }

    @Test fun scopeClearsOnThrowAndNestsAndRestores() {
        install()
        runCatching { withRequestOverrides(cookie) { error("boom") } }
        assertNull(RequestOverrides.current())
        withRequestOverrides(cookie) {
            withRequestOverrides(null) { assertFalse(Client(user).getFlag("lobby_hold")) }
            assertTrue(Client(user).getFlag("lobby_hold"))
        }
    }

    @Test fun suspendScopeSurvivesDispatcherHopsAndClears() = runBlocking {
        install()
        withRequestOverridesSuspend(cookie) {
            assertTrue(Client(user).getFlag("lobby_hold"))
            withContext(Dispatchers.IO) { assertTrue(Client(user).getFlag("lobby_hold")) }
            withContext(Dispatchers.Default) { assertTrue(Client(user).getFlag("lobby_hold")) }
        }
        assertFalse(Client(user).getFlag("lobby_hold"))
        withContext(Dispatchers.IO) { assertFalse(Client(user).getFlag("lobby_hold")) }
    }

    // ── servlet filter ───────────────────────────────────────────────────────
    private fun request(cookieHeader: String?): HttpServletRequest =
        Proxy.newProxyInstance(
            HttpServletRequest::class.java.classLoader,
            arrayOf(HttpServletRequest::class.java),
        ) { _, m, args ->
            when (m.name) {
                "getHeader" -> if ((args!![0] as String).equals("Cookie", true)) cookieHeader else null
                "isSecure" -> false
                else -> null
            }
        } as HttpServletRequest

    private fun response(): HttpServletResponse =
        Proxy.newProxyInstance(
            HttpServletResponse::class.java.classLoader,
            arrayOf(HttpServletResponse::class.java),
        ) { _, _, _ -> null } as HttpServletResponse

    @Test fun filterScopesOverridesForTheRequestAndClears() {
        install()
        var inside: Boolean? = null
        AnonIdFilter().doFilter(request("se_ov=$cookie"), response(), FilterChain { _, _ ->
            inside = Client(user).getFlag("lobby_hold")
        })
        assertEquals(true, inside)
        assertNull(RequestOverrides.current())
        assertFalse(Client(user).getFlag("lobby_hold"))
    }

    @Test fun filterClearsWhenChainThrows() {
        install()
        runCatching {
            AnonIdFilter().doFilter(request("se_ov=$cookie"), response(), FilterChain { _, _ -> error("x") })
        }
        assertNull(RequestOverrides.current())
    }

    @Test fun filterWithoutCookieChangesNothing() {
        install()
        var inside: Boolean? = null
        AnonIdFilter().doFilter(request(null), response(), FilterChain { _, _ -> inside = Client(user).getFlag("lobby_hold") })
        assertEquals(false, inside)
    }

    // ── unsigned dev cookie (se_ov_dev) ──────────────────────────────────────
    private val dev = "eyJnYXRlcyI6eyJsb2JieV9ob2xkIjp0cnVlLCJvbGRfbmF2IjpmYWxzZX0sImNvbmZpZ3MiOnsiYmFubmVyIjp7InRleHQiOiJoaSJ9fSwiZXhwcyI6eyJjaGVja291dF9leHAiOiJ0cmVhdG1lbnQifX0"

    // The suite pins shipeasy.env=production (InternalReportInertListener); dev-cookie
    // tests pick their own native env and restore it.
    private fun <T> withNativeEnv(value: String, block: () -> T): T {
        val saved = System.getProperty("shipeasy.env")
        System.setProperty("shipeasy.env", value)
        try { return block() } finally {
            if (saved != null) System.setProperty("shipeasy.env", saved) else System.clearProperty("shipeasy.env")
        }
    }

    private fun installDev(devOverrides: Boolean, env: String = "dev", key: Boolean = false): Engine {
        val e = Engine(apiKey = "", env = env, isNetworkEnabled = false, disableTelemetry = true, devOverrides = devOverrides)
        e.applyDataForTest(flagsBlob(key), exps)
        installEngineForTests(e)
        return e
    }

    @Test fun devVectorDecodes() {
        val ov = OverrideCookie.decodeDev(dev)
        assertNotNull(ov)
        assertEquals(mapOf("lobby_hold" to true, "old_nav" to false), ov.gates)
        assertEquals(mapOf("banner" to mapOf("text" to "hi")), ov.configs)
        assertEquals(mapOf("checkout_exp" to "treatment"), ov.exps)
        assertNull(OverrideCookie.decodeDev("not-json!!"))
        assertEquals(dev, OverrideCookie.extract("a=1; se_ov_dev=$dev; b=2", OverrideCookie.DEV_NAME))
        assertEquals(dev, OverrideCookie.extract("se_ov_dev=" + dev.replace("e", "%65"), OverrideCookie.DEV_NAME))
    }

    @Test fun devCookieIgnoredWhenOptionOff() = withNativeEnv("development") {
        installDev(devOverrides = false)
        withRequestOverrides("se_ov_dev=$dev") {
            assertFalse(Client(user).getFlag("lobby_hold"))
            assertNull(RequestOverrides.current())
        }
    }

    @Test fun devCookieIgnoredInProductionWithOneWarning() = withNativeEnv("production") {
        val records = mutableListOf<java.util.logging.LogRecord>()
        val h = object : java.util.logging.Handler() {
            override fun publish(r: java.util.logging.LogRecord) { records.add(r) }
            override fun flush() {}
            override fun close() {}
        }
        val logger = java.util.logging.Logger.getLogger("shipeasy")
        logger.addHandler(h)
        try {
            val e = installDev(devOverrides = true)
            assertFalse(e.devOverridesActive)
            withRequestOverrides("se_ov_dev=$dev") { assertFalse(Client(user).getFlag("lobby_hold")) }
            withRequestOverrides("se_ov_dev=$dev") { assertFalse(Client(user).getFlag("lobby_hold")) }
        } finally { logger.removeHandler(h) }
        assertEquals(1, records.count { it.message.contains("devOverrides") })
    }

    @Test fun devCookieHonoredInNonProdWithOption() = withNativeEnv("development") {
        installDev(devOverrides = true)
        withRequestOverrides("se_ov_dev=$dev") {
            val d = Client(user).getFlagDetail("lobby_hold")
            assertTrue(d.value)
            assertEquals(Reason.OVERRIDE, d.reason)
            assertEquals(mapOf("text" to "hi"), Client(user).getConfig("banner"))
            assertEquals("treatment", Client(user).universe("u1").assign().group)
        }
        assertFalse(Client(user).getFlag("lobby_hold"))
    }

    @Test fun devCookieHonoredUnderConfigureForTesting() {
        configureForTesting(devOverrides = true)
        withRequestOverrides("se_ov_dev=$dev") { assertTrue(Client(user).getFlag("lobby_hold")) }
        configureForTesting()
        withRequestOverrides("se_ov_dev=$dev") { assertFalse(Client(user).getFlag("lobby_hold")) }
    }

    @Test fun signedCookieBeatsDevCookieOnSameName() = withNativeEnv("development") {
        // Offline snapshot with a served key; dev says lobby_hold=true & old_nav=true,
        // signed says old_nav=false -> signed wins on old_nav, dev fills lobby_hold? (signed also sets it true)
        val e = Engine.fromSnapshot(flagsBlob(true), exps)
        e.devOverrides = true
        installEngineForTests(e)
        val devOnlyOldNavTrue = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"gates":{"old_nav":true,"only_dev":true}}""".toByteArray())
        withRequestOverrides("se_ov=$cookie; se_ov_dev=$devOnlyOldNavTrue") {
            assertFalse(Client(user).getFlag("old_nav"))            // signed wins
            assertTrue(Client(user).getFlagDetail("only_dev").value) // dev fills the rest
            assertTrue(Client(user).getFlag("lobby_hold"))           // signed
        }
    }

    // ── X-Shipeasy-Overrides header fallback ─────────────────────────────────
    @Test fun headerOnlySignedCookieApplies() {
        install()
        withRequestOverrides(null, "se_ov=$cookie") { assertTrue(Client(user).getFlag("lobby_hold")) }
        withRequestOverrides("a=1", "se_ov=$cookie") { assertTrue(Client(user).getFlag("lobby_hold")) }
    }

    @Test fun headerOnlyDevCookieAppliesOnlyUnderGuards() = withNativeEnv("development") {
        installDev(devOverrides = true)
        withRequestOverrides(null, "se_ov_dev=$dev") { assertTrue(Client(user).getFlag("lobby_hold")) }
        installDev(devOverrides = false)
        withRequestOverrides(null, "se_ov_dev=$dev") { assertFalse(Client(user).getFlag("lobby_hold")) }
    }

    @Test fun unrelatedDottedCookieIsNotABareValueAndHeaderFallbackStillApplies() {
        assertNull(OverrideCookie.extract("theme=a.b"))
        assertNull(OverrideCookie.extract("theme=a.b", OverrideCookie.DEV_NAME))
        assertEquals(cookie, OverrideCookie.extract(cookie))
        install()
        withRequestOverrides("theme=a.b", "se_ov=$cookie") { assertTrue(Client(user).getFlag("lobby_hold")) }
    }

    @Test fun cookieBeatsHeaderForSameName() {
        install()
        // Cookie carries a tampered signed value; the header's valid one must NOT fill in.
        withRequestOverrides("se_ov=$tampered", "se_ov=$cookie") { assertFalse(Client(user).getFlag("lobby_hold")) }
    }

    @Test fun filterReadsOverridesHeader() {
        install()
        val req = Proxy.newProxyInstance(
            HttpServletRequest::class.java.classLoader, arrayOf(HttpServletRequest::class.java),
        ) { _, m, args ->
            when (m.name) {
                "getHeader" -> if ((args!![0] as String).equals("X-Shipeasy-Overrides", true)) "se_ov=$cookie" else null
                "isSecure" -> false
                else -> null
            }
        } as HttpServletRequest
        var inside: Boolean? = null
        AnonIdFilter().doFilter(req, response(), FilterChain { _, _ -> inside = Client(user).getFlag("lobby_hold") })
        assertEquals(true, inside)
        assertNull(RequestOverrides.current())
    }
}
