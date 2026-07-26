package ai.shipeasy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BootstrapTest {
    private fun client(): Engine = Engine.fromSnapshot(
        flags = mapOf(
            "gates" to mapOf(
                "new_ui" to mapOf("enabled" to true, "rolloutPct" to 10000, "salt" to "s"),
                "off_gate" to mapOf("enabled" to false, "rolloutPct" to 10000, "salt" to "s"),
            ),
            "configs" to mapOf("theme" to mapOf("value" to mapOf("color" to "blue"))),
        ),
        experiments = mapOf("experiments" to emptyMap<String, Any?>(), "universes" to emptyMap<String, Any?>()),
    )

    @Test fun evaluateBuildsPayload() {
        val p = client().evaluate(mapOf("user_id" to "u1"))
        @Suppress("UNCHECKED_CAST") val flags = p["flags"] as Map<String, Any?>
        assertEquals(true, flags["new_ui"])
        assertEquals(false, flags["off_gate"])
        @Suppress("UNCHECKED_CAST") val ks = p["killswitches"] as Map<String, Any?>
        assertTrue(ks.isEmpty())
    }

    @Test fun bootstrapScriptTagAttrs() {
        val tag = client().bootstrapScriptTag(mapOf("user_id" to "u1"), anonId = "anon-1")
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/sdk/runtime.js\""))
        assertTrue(tag.contains("data-se-bootstrap"))
        assertTrue(tag.contains("data-anon-id=\"anon-1\""))
        assertTrue(tag.contains("data-i18n-profile=\"en:prod\""))
        assertFalse(tag.contains("data-key"))

        val raw = Regex("data-flags=\"([^\"]*)\"").find(tag)!!.groupValues[1]
        val decoded = raw.replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
        val flags = Json.parseToJsonElement(decoded).jsonObject
        assertEquals("true", flags["new_ui"]!!.jsonPrimitive.content)
    }

    @Test fun bootstrapScriptTagOmitsAnonWhenUnset() {
        val tag = client().bootstrapScriptTag(mapOf("user_id" to "u1"))
        assertFalse(tag.contains("data-anon-id"))
    }

    @Test fun bootstrapScriptTagCarriesIdentifiedUser() {
        val tag = client().bootstrapScriptTag(
            mapOf("user_id" to "u1", "email" to "a@b.com", "anonymous_id" to "anon-1"),
            anonId = "anon-1",
        )
        assertTrue(tag.contains("data-anon-id=\"anon-1\""))
        val raw = Regex("data-user=\"([^\"]*)\"").find(tag)!!.groupValues[1]
        val decoded = raw.replace("&quot;", "\"").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
        // Keys sorted; anonymous_id dropped.
        assertEquals("{\"email\":\"a@b.com\",\"user_id\":\"u1\"}", decoded)
    }

    @Test fun bootstrapScriptTagOmitsUserWhenAnonymous() {
        // Only anonymous_id → no identifying trait remains.
        assertFalse(
            client().bootstrapScriptTag(mapOf("anonymous_id" to "anon-1"), anonId = "anon-1")
                .contains("data-user"),
        )
        // Empty user → no data-user.
        assertFalse(client().bootstrapScriptTag(emptyMap()).contains("data-user"))
    }

    @Test fun i18nScriptTag() {
        val tag = client().i18nScriptTag("client_pub", "fr:prod")
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/sdk/i18n/loader.js\""))
        assertTrue(tag.contains("data-key=\"client_pub\""))
        assertTrue(tag.contains("data-profile=\"fr:prod\""))
    }

    // --- every argument is optional: the tags read what configure() set ------

    /** A test-mode engine carrying the SSR tag defaults configure() would set. */
    private fun configured(): Engine = Engine.forTesting(
        clientKey = "sdk_client_cfg",
        profile = "fr:prod",
        projectId = "proj_cfg",
        cdnBaseUrl = "https://cdn.example.test",
    )

    @Test fun i18nScriptTagDefaultsFromConfigure() {
        val tag = configured().i18nScriptTag()
        assertTrue(tag.contains("src=\"https://cdn.example.test/sdk/i18n/loader.js\""))
        assertTrue(tag.contains("data-key=\"sdk_client_cfg\""))
        assertTrue(tag.contains("data-profile=\"fr:prod\""))
    }

    @Test fun bootstrapScriptTagNeedsNoUser() {
        val tag = configured().bootstrapScriptTag()
        assertTrue(tag.contains("src=\"https://cdn.example.test/sdk/runtime.js\""))
        assertTrue(tag.contains("data-i18n-profile=\"fr:prod\""))
        assertFalse(tag.contains("data-user"))
    }

    @Test fun devtoolsScriptTagDefaultsFromConfigure() {
        val tag = configured().devtoolsScriptTag()
        assertTrue(tag.contains("src=\"https://cdn.example.test/se-devtools.js\""))
        assertTrue(tag.contains("data-project-id=\"proj_cfg\""))
        assertTrue(tag.contains("data-client-api-key=\"sdk_client_cfg\""))
        assertTrue(tag.contains("defer"))
    }

    @Test fun explicitArgumentsStillWin() {
        val engine = configured()
        val i18n = engine.i18nScriptTag("other_key", "de:prod")
        assertTrue(i18n.contains("data-key=\"other_key\""))
        assertTrue(i18n.contains("data-profile=\"de:prod\""))

        val boot = engine.bootstrapScriptTag(mapOf("user_id" to "u1"), i18nProfile = "de:prod")
        assertTrue(boot.contains("data-i18n-profile=\"de:prod\""))

        val dev = engine.devtoolsScriptTag("proj_other", clientKey = "other_key", defer = false)
        assertTrue(dev.contains("data-project-id=\"proj_other\""))
        assertTrue(dev.contains("data-client-api-key=\"other_key\""))
        assertFalse(dev.contains("defer"))
    }

    @Test fun devtoolsTagRendersWhenUnconfigured() {
        // A missing project id / client key renders anyway (the browser bundle
        // reports what it needs) — a tag helper must never break a template.
        val tag = client().devtoolsScriptTag()
        assertTrue(tag.contains("src=\"https://cdn.shipeasy.ai/se-devtools.js\""))
        assertTrue(tag.contains("data-project-id=\"\""))
    }
}
