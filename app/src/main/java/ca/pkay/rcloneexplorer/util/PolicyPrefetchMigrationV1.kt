package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.workmanager.PolicyFolderPrefetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One-time migration that enqueues [PolicyFolderPrefetchWorker] for every folder already
 * toggled ON in [MediaFolderPolicy] before this feature shipped. Guarded by a boolean pref so
 * it fires exactly once per install.
 *
 * All IO (rclone config read + WorkManager enqueue) runs on [Dispatchers.IO]; the caller may
 * invoke [runIfNeeded] from any thread including the main thread.
 */
object PolicyPrefetchMigrationV1 {

    private const val TAG = "PolicyPrefetchMigV1"
    internal const val PREF_DONE = "policy_prefetch_migration_v1_done"

    /**
     * Entry point for production callers (e.g. [ca.pkay.rcloneexplorer.Activities.MainActivity]).
     * Dispatches to [Dispatchers.IO] and returns immediately.
     */
    @JvmStatic
    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runIfNeeded(
                prefs = PreferenceManager.getDefaultSharedPreferences(appContext),
                remoteSupplier = { Rclone(appContext).remotes },
                enqueueThumbnail = { remote, folder ->
                    PolicyFolderPrefetchWorker.enqueueThumbnail(appContext, remote, folder)
                },
                enqueueCache = { remote, folder ->
                    PolicyFolderPrefetchWorker.enqueueCache(appContext, remote, folder)
                },
            )
        }
    }

    /**
     * Injectable overload for unit tests — Context is not required because the enqueue lambdas
     * already capture it (or substitute a no-op in tests).
     */
    internal fun runIfNeeded(
        prefs: SharedPreferences,
        remoteSupplier: () -> List<RemoteItem>,
        enqueueThumbnail: (String, String) -> Unit,
        enqueueCache: (String, String) -> Unit,
    ) {
        if (prefs.getBoolean(PREF_DONE, false)) {
            FLog.d(TAG, "Migration already done, skipping")
            return
        }

        val remotes = try {
            remoteSupplier()
        } catch (e: Exception) {
            FLog.e(TAG, "Failed to list remotes during migration", e)
            emptyList()
        }

        for (remote in remotes) {
            if (!MediaFolderPolicy.shouldApplyAllowListGating(remote)) {
                continue
            }
            val rows = try {
                MediaFolderPolicy.readPolicyRows(prefs, remote.name)
            } catch (e: Exception) {
                FLog.e(TAG, "Failed to read policy rows for remote=${remote.name}", e)
                continue
            }
            for (row in rows) {
                if (row.thumbnail) {
                    try {
                        enqueueThumbnail(remote.name, row.path)
                        FLog.d(TAG, "Enqueued thumb prefetch remote=${remote.name} folder=${row.path}")
                    } catch (e: Exception) {
                        FLog.e(TAG, "Failed to enqueue thumbnail for remote=${remote.name} folder=${row.path}", e)
                    }
                }
                if (row.cache) {
                    try {
                        enqueueCache(remote.name, row.path)
                        FLog.d(TAG, "Enqueued cache prefetch remote=${remote.name} folder=${row.path}")
                    } catch (e: Exception) {
                        FLog.e(TAG, "Failed to enqueue cache for remote=${remote.name} folder=${row.path}", e)
                    }
                }
            }
        }

        prefs.edit().putBoolean(PREF_DONE, true).apply()
        FLog.d(TAG, "Migration complete")
    }
}
