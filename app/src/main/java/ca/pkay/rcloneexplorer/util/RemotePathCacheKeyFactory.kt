package ca.pkay.rcloneexplorer.util

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Stable [CacheKeyFactory] for [SelectedFolderSimpleCacheProvider]'s [SimpleCache].
 *
 * The rclone thumbnail server assigns a new random port and auth token on every app session,
 * so per-session URLs cannot serve as cache keys. This factory strips the per-session prefix
 * (auth token) from the URL path and returns a key of the form:
 *
 *   `<remoteName>/<remoteRelativePath>`
 *
 * which is stable across sessions as long as the file does not move.
 *
 * URL path shape: `/<authToken>/<remoteName>/<remoteRelativePath>`
 *
 * IMPORTANT: This factory MUST be used everywhere [SelectedFolderSimpleCacheProvider]'s cache is
 * read or written. Mixing it with [CacheKeyFactory.DEFAULT] (URL-based keys) would make any
 * previously-written bytes inaccessible to the component using this factory, and vice versa.
 * Use [SelectedFolderSimpleCacheProvider.keyFactory] to obtain the single shared instance.
 */
object RemotePathCacheKeyFactory : CacheKeyFactory {

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val stableKey = extractStableKey(dataSpec.uri.path)
        return stableKey ?: CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
    }
}

/**
 * Extracts a session-stable cache key from a rclone-serve-http URL path.
 *
 * The expected path structure is `/<authToken>/<remoteName>/<remoteRelativePath>`.
 * The auth-token segment (first non-empty path component) is dropped; the remainder
 * `<remoteName>/<remoteRelativePath>` is returned as the stable key.
 *
 * Returns `null` when the path does not match the expected structure (fewer than two
 * path segments after the leading slash), signalling the caller to fall back to the
 * default URL-based key.
 */
internal fun extractStableKey(uriPath: String?): String? {
    if (uriPath.isNullOrEmpty()) return null
    val normalized = if (uriPath.startsWith("/")) uriPath.drop(1) else uriPath
    val authTokenEnd = normalized.indexOf('/')
    if (authTokenEnd < 0) return null
    val stableKey = normalized.substring(authTokenEnd + 1)
    return if (stableKey.isEmpty()) null else stableKey
}
