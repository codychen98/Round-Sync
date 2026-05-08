package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences

/**
 * Identifies a specific version of a remote video file for thumbnail failure tracking.
 *
 * [sizeBytes] and [mtimeEpochMs] are included so that a marker written for an old version of a
 * file does not suppress thumbnail loading after the file is replaced with a new upload — the
 * new file's key will differ. Values of 0 are accepted as "unknown" and match only other
 * zero-field keys.
 */
data class BlacklistKey(
    val remoteName: String,
    val remoteRelativePath: String,
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
) {
    /**
     * Encodes this key as a single string for storage in a [SharedPreferences] StringSet.
     * Format: `<sizeBytes>|<mtimeEpochMs>|<remoteName>|<remoteRelativePath>`.
     *
     * Numeric fields come first so [remoteRelativePath], which may contain '|', is always the
     * last field and is fully captured when decoding with a split limit of 4.
     */
    fun encode(): String = "$sizeBytes|$mtimeEpochMs|$remoteName|$remoteRelativePath"

    companion object {
        /**
         * Inverse of [encode]. Returns null if [encoded] cannot be parsed.
         */
        fun decode(encoded: String): BlacklistKey? {
            val parts = encoded.split("|", limit = 4)
            if (parts.size != 4) return null
            return try {
                BlacklistKey(
                    sizeBytes = parts[0].toLong(),
                    mtimeEpochMs = parts[1].toLong(),
                    remoteName = parts[2],
                    remoteRelativePath = parts[3],
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/**
 * Persists per-file thumbnail failure markers in a [SharedPreferences] StringSet.
 *
 * A marker is written after every fallback strategy (MMR -> Exo -> B2 once Step 5 lands) has
 * failed for the same file. The marker prevents indefinite retries for known-unfetchable files.
 *
 * Thread safety: all public methods may be called from any thread; [SharedPreferences.edit] with
 * [apply] provides the write ordering sufficient here (last-write-wins on concurrent marks for
 * the same file is acceptable).
 */
object ThumbnailFailureBlacklist {

    internal const val PREF_KEY = "thumbnail_failure_blacklist_v1"

    @JvmStatic
    fun isBlacklisted(prefs: SharedPreferences, key: BlacklistKey): Boolean {
        val set = prefs.getStringSet(PREF_KEY, null) ?: return false
        return set.contains(key.encode())
    }

    @JvmStatic
    fun mark(prefs: SharedPreferences, key: BlacklistKey) {
        val current = prefs.getStringSet(PREF_KEY, null)
        val updated: HashSet<String> = if (current != null) HashSet(current) else HashSet()
        updated.add(key.encode())
        prefs.edit().putStringSet(PREF_KEY, updated).apply()
    }

    /** Removes the marker for a single file. Used by the "Retry thumbnail" gesture in Step 13. */
    @JvmStatic
    fun clear(prefs: SharedPreferences, key: BlacklistKey) {
        val current = prefs.getStringSet(PREF_KEY, null) ?: return
        val updated = HashSet(current)
        if (!updated.remove(key.encode())) return
        prefs.edit().putStringSet(PREF_KEY, updated).apply()
    }

    /** Removes all markers. Used by the "Clear thumbnail cache" Settings button in Step 12. */
    @JvmStatic
    fun clearAll(prefs: SharedPreferences) {
        prefs.edit().remove(PREF_KEY).apply()
    }
}
