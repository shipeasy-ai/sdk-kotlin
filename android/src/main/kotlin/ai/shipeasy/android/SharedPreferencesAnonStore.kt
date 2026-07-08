package ai.shipeasy.android

import ai.shipeasy.AnonStore
import android.content.Context
import android.content.SharedPreferences

/**
 * [AnonStore] backed by Android [SharedPreferences]. Persists the device
 * `anonymous_id` across app launches so a logged-out visitor buckets identically
 * on every cold start (without persistence a fresh UUID each launch silently
 * re-buckets every fractional rollout and experiment).
 *
 * Uses the application context, so it's safe to hold for the process lifetime.
 * Reads are synchronous; writes use `apply()` (async disk flush, immediate
 * in-memory visibility), matching the best-effort [AnonStore] contract.
 *
 * For at-rest encryption, back the store with `EncryptedSharedPreferences` and
 * pass those prefs' name — or implement [AnonStore] directly over DataStore.
 *
 * ```kotlin
 * // Application.onCreate:
 * configureAndroid(this, clientKey = "pk_live_…")
 * // or explicitly:
 * configureClient(clientKey = "pk_live_…", store = SharedPreferencesAnonStore(this))
 * ```
 */
class SharedPreferencesAnonStore @JvmOverloads constructor(
    context: Context,
    prefsName: String = "shipeasy",
) : AnonStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
