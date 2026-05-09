package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-time migration that wipes every [ThumbnailFailureBlacklist] entry written while the
 * URL-staleness bug (stale port+auth after ThumbnailServerManager respawn) was in effect.
 *
 * Without this wipe, previously-blacklisted files such as large non-progressive MKVs would
 * remain permanently suppressed even after Steps 14-15 ship the URL-re-resolution fix.
 *
 * Guarded by [PREF_DONE] so the wipe fires exactly once per install. Idempotent: if the
 * blacklist is already empty, [PREF_DONE] is still set so the next launch is a no-op.
 *
 * All SharedPreferences IO runs on [Dispatchers.IO]; the caller may invoke [runIfNeeded]
 * from any thread including the main thread.
 */
object BlacklistMigrationV2 {

    private const val TAG = "BlacklistMigV2"
    internal const val PREF_DONE = "blacklist_v2_migration_done"

    /**
     * Entry point for production callers (e.g. [ca.pkay.rcloneexplorer.Activities.MainActivity]).
     * Dispatches to [Dispatchers.IO] and returns immediately.
     */
    @JvmStatic
    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runIfNeeded(PreferenceManager.getDefaultSharedPreferences(appContext))
        }
    }

    /**
     * Injectable overload for unit tests — Context is not required because [prefs] is passed
     * directly. Runs synchronously on the caller's thread (tests do not use coroutines here).
     */
    internal fun runIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_DONE, false)) {
            FLog.d(TAG, "Migration already done, skipping")
            return
        }
        ThumbnailFailureBlacklist.clearAll(prefs)
        prefs.edit().putBoolean(PREF_DONE, true).apply()
        FLog.d(TAG, "Blacklist cleared, migration complete")
    }
}
