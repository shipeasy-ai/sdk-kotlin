package ai.shipeasy

/**
 * Native runtime-environment detection.
 *
 * Used ONLY to pick the DEFAULT for outbound egress when the caller does not set
 * it explicitly:
 *   - is the SDK allowed to make network requests at all ([Engine]'s
 *     `isNetworkEnabled`)?
 *   - is per-evaluation usage telemetry / logging allowed (`isTrackingEnabled`)?
 *
 * Both default to ON in production and OFF everywhere else, so a local/dev/CI run
 * of an app that embeds the SDK never phones home unless it explicitly opts in.
 *
 * Precedence for the production decision:
 *   1. A native runtime signal — the system property `shipeasy.env`, then the env
 *      vars `SHIPEASY_ENV`, `APP_ENV`, `ENV` (in that order). A value of
 *      "production"/"prod" (case-insensitive) ⇒ prod; anything else present
 *      ("development"/"staging"/"test"/…) ⇒ not prod.
 *   2. When no native signal is set (e.g. serverless / mobile, where the app never
 *      exports one), fall back to the SDK's own configured `env` option, which the
 *      caller sets and which itself defaults to "prod". This keeps a real
 *      production deploy "on" by default while an `env = "dev"` config stays quiet.
 *
 * The `env` option is always present (it defaults to "prod"), so the production
 * decision is always inferable — the SDK never has to make the fields required.
 */
internal object Env {
    // The env vars / system property consulted, in precedence order. The system
    // property `shipeasy.env` wins so a test (or a launch flag) can force prod
    // without mutating the process environment.
    private const val SYS_PROP = "shipeasy.env"
    private val ENV_VARS = listOf("SHIPEASY_ENV", "APP_ENV", "ENV")

    /**
     * True when the host runtime looks like a production deployment. [configuredEnv]
     * is the SDK's own `env` option (dev/staging/prod); it is consulted only when no
     * native runtime signal is set.
     */
    fun isProductionEnv(configuredEnv: String? = null): Boolean {
        val native = readNativeEnv()
        if (native != null) return native == "production" || native == "prod"
        return (configuredEnv ?: "prod").trim().lowercase() == "prod"
    }

    /**
     * Read the native runtime environment string, lowercased, or null when nothing
     * is set. Checks the `shipeasy.env` system property first, then the env vars in
     * [ENV_VARS] order. Best-effort — a SecurityManager that forbids the lookup
     * degrades to null (fall back to the configured env).
     */
    private fun readNativeEnv(): String? = runCatching {
        val fromProp = System.getProperty(SYS_PROP)
        val raw = if (!fromProp.isNullOrBlank()) fromProp
        else ENV_VARS.firstNotNullOfOrNull { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
        raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
