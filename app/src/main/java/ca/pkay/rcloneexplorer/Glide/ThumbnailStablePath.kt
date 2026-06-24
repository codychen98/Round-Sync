package ca.pkay.rcloneexplorer.Glide

import android.net.Uri

/**
 * Canonical stable path for thumbnail cache keys and reload epoch maps.
 * Explorer paths use literal spaces; HTTP URL paths may use percent-encoding.
 */
object ThumbnailStablePath {

    @JvmStatic
    fun normalize(stablePath: String): String {
        if (stablePath.isEmpty()) {
            return stablePath
        }
        return try {
            Uri.decode(stablePath)
        } catch (_: Throwable) {
            stablePath
        }
    }

    @JvmStatic
    fun fromServeUrl(url: String): String {
        val path = Uri.parse(url).path ?: return normalize(url)
        if (path.isEmpty()) {
            return normalize(url)
        }
        val secondSlash = path.indexOf('/', 1)
        val stable = if (secondSlash > 0) path.substring(secondSlash) else path
        return normalize(stable)
    }
}
