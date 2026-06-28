package ai.shipeasy.guide

import androidx.compose.ui.graphics.Color

/**
 * One Shipeasy entity rendered as a card in the guide.
 *
 * Every [value] here is a HARDCODED PLACEHOLDER. None of these come from the
 * SDK — the SDK is intentionally not a dependency of this sample yet. The
 * [call] string is shown verbatim on the card as the real SDK call you would
 * make to produce that value once `ai.shipeasy:shipeasy-kotlin` is installed.
 */
data class Entity(
    /** UPPERCASE type label shown in the accent chip, e.g. "FEATURE FLAG". */
    val label: String,
    /** Accent color for this entity's chips. */
    val accent: Color,
    /** The entity key, shown monospace as the card title, e.g. "new_checkout". */
    val key: String,
    /** Short placeholder value/summary shown in the accent value chip. */
    val value: String,
    /** One-line plain-language description of what the entity is. */
    val description: String,
    /** The real SDK call, rendered as a monospace code block. */
    val call: String,
    /** Faint meta line under the code block. */
    val meta: String,
)

/**
 * The guide content, in display order. The client handle is referred to as `c`
 * throughout (as in `val c = Shipeasy.client(...)`).
 *
 * REMINDER: replace each placeholder by installing the SDK and wiring the call
 * shown on the card — see the `// TODO: once ai.shipeasy:shipeasy-kotlin is
 * installed` blocks in MainActivity.kt.
 */
val GUIDE_ENTITIES: List<Entity> = listOf(
    Entity(
        label = "FEATURE FLAG",
        accent = SeColors.Green,
        key = "new_checkout",
        value = "true · RULE_MATCH",
        description = "A boolean on/off switch with targeting rules + percentage rollout.",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed
        // val on = c.getFlag("new_checkout", mapOf("user_id" to "u_123"))
        call = "val on = c.getFlag(\"new_checkout\", mapOf(\"user_id\" to \"u_123\"))",
        meta = "evaluated for user_id=u_123 · placeholder",
    ),
    Entity(
        label = "DYNAMIC CONFIG",
        accent = SeColors.Blue,
        key = "billing_copy",
        value = "{ headline, cta }",
        description = "A typed JSON blob you change without deploying.",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed
        // val cfg = c.getConfig("billing_copy")
        call = "val cfg = c.getConfig(\"billing_copy\")",
        meta = "{\"headline\":\"Welcome back 👋\",\"cta\":\"Upgrade to Pro\"} · placeholder",
    ),
    Entity(
        label = "A/B EXPERIMENT",
        accent = SeColors.Purple,
        key = "checkout_button",
        value = "treatment · in experiment",
        description = "Splits users into variants and measures a metric.",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed
        // val r = c.getExperiment(
        //     "checkout_button",
        //     mapOf("user_id" to "u_123"),
        //     mapOf("color" to "#888", "label" to "Buy"),
        // )
        call = "val r = c.getExperiment(\"checkout_button\", mapOf(\"user_id\" to \"u_123\"), mapOf(\"color\" to \"#888\",\"label\" to \"Buy\"))",
        meta = "params {\"color\":\"#34d399\",\"label\":\"Buy now\"} · placeholder",
    ),
    Entity(
        label = "KILL SWITCH",
        accent = SeColors.Red,
        key = "payments_paused",
        value = "false · payments live",
        description = "An operational off-switch shipped alongside flags — flip it to disable a subsystem during an incident.",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed
        // val boot = c.evaluate(mapOf("user_id" to "u_123"))
        // val paused = boot["killswitches"]?.get("payments_paused")
        call = "val boot = c.evaluate(mapOf(\"user_id\" to \"u_123\")); val paused = boot[\"killswitches\"]?.get(\"payments_paused\")",
        meta = "subsystem enabled while false · placeholder",
    ),
    Entity(
        label = "EVENT / METRIC",
        accent = SeColors.Cyan,
        key = "checkout_completed",
        value = "queued · revenue 49.99",
        description = "Fire-and-forget events that power experiment metrics + dashboards.",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed
        // c.track("u_123", "checkout_completed", mapOf("revenue" to 49.99, "plan" to "pro"))
        call = "c.track(\"u_123\", \"checkout_completed\", mapOf(\"revenue\" to 49.99, \"plan\" to \"pro\"))",
        meta = "last event queued · props {\"revenue\":49.99,\"plan\":\"pro\"} · placeholder",
    ),
    Entity(
        label = "I18N LABEL",
        accent = SeColors.Amber,
        key = "hero.title",
        value = "Ship features, not stress",
        description = "Server-managed copy you translate + publish from the dashboard — no redeploy. (i18n for the Kotlin SDK ships as a follow-up; shown here for completeness.)",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed (illustrative — i18n is a follow-up)
        // val title = t("hero.title", mapOf("name" to "Sam"))
        call = "t(\"hero.title\", mapOf(\"name\" to \"Sam\"))",
        meta = "illustrative · i18n ships in a follow-up release · placeholder",
    ),
    Entity(
        label = "ERROR REPORTING",
        accent = SeColors.Red,
        key = "see()",
        value = "0 issues reported this session",
        description = "Structured error reports that document the product consequence, not just a stack trace.",
        // TODO: once ai.shipeasy:shipeasy-kotlin is installed
        // try {
        //     submitOrder(o)
        // } catch (e: Exception) {
        //     Shipeasy.see(e).causesThe("checkout").to("use cached prices")
        //         .extras(mapOf("order_id" to o.id))
        // }
        call = "try { submitOrder(o) } catch (e: Exception) {\n    Shipeasy.see(e).causesThe(\"checkout\").to(\"use cached prices\")\n        .extras(mapOf(\"order_id\" to o.id))\n}",
        meta = "consequence-first error reports · placeholder",
    ),
)
