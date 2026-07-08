package ai.shipeasy.android

import ai.shipeasy.ShipeasyClient
import ai.shipeasy.configureClient
import android.content.Context

/**
 * Configure the Shipeasy native client for an Android app in one call.
 *
 * Wires SharedPreferences-backed persistence for the device `anonymous_id`
 * (so bucketing is stable across app launches) and delegates to the core
 * [configureClient]. Call once at app launch — typically `Application.onCreate`:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         configureAndroid(this, clientKey = BuildConfig.SHIPEASY_CLIENT_KEY)
 *     }
 * }
 *
 * // Later, from a coroutine (e.g. a ViewModel):
 * shipeasyClient()?.identify(mapOf("user_id" to userId))
 * val on = shipeasyClient()?.getFlag("new_checkout") ?: false
 * ```
 *
 * Use your **public client key** (`pk_…`) — never a server key in a shipped app.
 * First-config-wins, mirroring [configureClient].
 */
@JvmOverloads
fun configureAndroid(
    context: Context,
    clientKey: String,
    baseUrl: String? = null,
    env: String = "prod",
    disableTelemetry: Boolean = false,
    telemetryUrl: String? = null,
    privateAttributes: List<String> = emptyList(),
): ShipeasyClient = configureClient(
    clientKey = clientKey,
    store = SharedPreferencesAnonStore(context),
    baseUrl = baseUrl,
    env = env,
    disableTelemetry = disableTelemetry,
    telemetryUrl = telemetryUrl,
    privateAttributes = privateAttributes,
)
