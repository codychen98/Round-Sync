package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Storage layout and product constants for **selected-folder media cache** (Problem 2,
 * `Roadmap/feature.md`). Thumbnail disk cache (Glide) and rclone VFS temp paths stay separate.
 *
 * **Mechanism (v1 design): hybrid**
 * - **Progressive video/audio in ExoPlayer:** Media3 `SimpleCache` under [exoSimpleCacheDir].
 *   LRU by total bytes; fits range-based HTTP(S) reads from local rclone `serve` URLs.
 * - **In-app images and other direct HTTP reads** that should share this feature: bounded OkHttp
 *   `Cache` on disk under [okHttpHttpCacheDir]. Same origin URLs as streaming; use this directory
 *   only for cache-enabled clients in F.2 so it stays isolated from other OkHttp usage.
 *
 * **Quota:** One user-facing cap ([DEFAULT_MAX_CACHE_BYTES] default). F.2 should assign
 * internal budgets (e.g. sum of Exo + OkHttp max sizes) that do not exceed the clamped preference.
 *
 * **Physical roots (typical device):**
 * `{context.filesDir}/[BASE_DIR_NAME]/` — internal app storage; survives until app uninstall or
 * explicit clear (F.3). Not under `cacheDir`, to avoid aggressive system eviction of large data.
 */
object SelectedFolderMediaCacheLayout {

    /** SharedPreferences key for persisted max cache size (bytes), Phase F wiring. */
    const val PREF_KEY_MAX_CACHE_BYTES_V1 = "selected_folder_media_cache_max_bytes_v1"

    /** Directory under [Context.getFilesDir] holding all selected-folder cache data. */
    const val BASE_DIR_NAME = "selected_folder_media_cache"

    /** Subdirectory for ExoPlayer `SimpleCache` (Media3). */
    const val SUBDIR_EXO_SIMPLE_CACHE = "exo_simple_cache"

    /** Subdirectory for OkHttp `Cache` used for non-ExoPlayer HTTP responses (e.g. images). */
    const val SUBDIR_OKHTTP_HTTP_CACHE = "okhttp_http_cache"

    /** Default total quota: 10 GiB (feature.md Q5). */
    const val DEFAULT_MAX_CACHE_BYTES: Long = 10L * 1024L * 1024L * 1024L

    /** Minimum user-visible / stored cap: 256 MiB. */
    const val MIN_MAX_CACHE_BYTES: Long = 256L * 1024L * 1024L

    /** Maximum stored cap: 128 GiB (upper product bound; devices may have less free space). */
    const val MAX_MAX_CACHE_BYTES: Long = 128L * 1024L * 1024L * 1024L

    /**
     * Default split of the total quota between subsystems (numerators over [QUOTA_SPLIT_DENOMINATOR]).
     * F.2 may refine; sums must not exceed the denominator.
     */
    const val QUOTA_SPLIT_DENOMINATOR: Int = 100_000
    const val QUOTA_SHARE_EXO_NUMERATOR: Int = 70_000
    const val QUOTA_SHARE_OKHTTP_NUMERATOR: Int = 30_000

    init {
        require(QUOTA_SHARE_EXO_NUMERATOR + QUOTA_SHARE_OKHTTP_NUMERATOR <= QUOTA_SPLIT_DENOMINATOR) {
            "Quota split numerators must not exceed denominator"
        }
    }

    fun baseDir(filesDir: File): File = File(filesDir, BASE_DIR_NAME)

    fun exoSimpleCacheDir(filesDir: File): File =
        File(baseDir(filesDir), SUBDIR_EXO_SIMPLE_CACHE)

    fun okHttpHttpCacheDir(filesDir: File): File =
        File(baseDir(filesDir), SUBDIR_OKHTTP_HTTP_CACHE)

    fun baseDir(context: Context): File = baseDir(context.filesDir)

    fun exoSimpleCacheDir(context: Context): File = exoSimpleCacheDir(context.filesDir)

    fun okHttpHttpCacheDir(context: Context): File = okHttpHttpCacheDir(context.filesDir)

    /**
     * Clamps a requested max-cache value to [[MIN_MAX_CACHE_BYTES], [MAX_MAX_CACHE_BYTES]].
     */
    fun clampMaxCacheBytes(requested: Long): Long =
        requested.coerceIn(MIN_MAX_CACHE_BYTES, MAX_MAX_CACHE_BYTES)

    fun exoMaxBytesForTotal(totalMaxBytes: Long): Long {
        val total = clampMaxCacheBytes(totalMaxBytes)
        return (total * QUOTA_SHARE_EXO_NUMERATOR) / QUOTA_SPLIT_DENOMINATOR
    }

    fun okHttpMaxBytesForTotal(totalMaxBytes: Long): Long {
        val total = clampMaxCacheBytes(totalMaxBytes)
        return (total * QUOTA_SHARE_OKHTTP_NUMERATOR) / QUOTA_SPLIT_DENOMINATOR
    }

    fun readClampedTotalMaxBytesFromPrefs(prefs: SharedPreferences): Long =
        clampMaxCacheBytes(prefs.getLong(PREF_KEY_MAX_CACHE_BYTES_V1, DEFAULT_MAX_CACHE_BYTES))
}
