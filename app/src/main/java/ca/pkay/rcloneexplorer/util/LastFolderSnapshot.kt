package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Persists the last file-explorer folder (remote + directory path) for background
 * thumbnail prefetch (Phase E). Non-recursive listing is implied by the stored path only.
 */
data class LastFolderSnapshot(
    val remoteName: String,
    val directoryPath: String,
)

object LastFolderSnapshotStore {

    private const val KEY_REMOTE = "media_prefetch_last_remote_v1"
    private const val KEY_DIRECTORY_PATH = "media_prefetch_last_directory_path_v1"

    @JvmStatic
    fun persist(context: Context, remoteName: String?, directoryPath: String?) {
        persist(PreferenceManager.getDefaultSharedPreferences(context), remoteName, directoryPath)
    }

    @JvmStatic
    fun persist(prefs: SharedPreferences, remoteName: String?, directoryPath: String?) {
        val r = remoteName?.trim().orEmpty()
        val p = directoryPath?.trim().orEmpty()
        if (r.isEmpty() || p.isEmpty()) {
            return
        }
        prefs.edit()
            .putString(KEY_REMOTE, r)
            .putString(KEY_DIRECTORY_PATH, p)
            .apply()
    }

    @JvmStatic
    fun read(prefs: SharedPreferences): LastFolderSnapshot? {
        val r = prefs.getString(KEY_REMOTE, null)?.trim().orEmpty()
        val p = prefs.getString(KEY_DIRECTORY_PATH, null)?.trim().orEmpty()
        if (r.isEmpty() || p.isEmpty()) {
            return null
        }
        return LastFolderSnapshot(remoteName = r, directoryPath = p)
    }
}
