package ca.pkay.rcloneexplorer.Glide

import android.net.Uri
import java.net.MalformedURLException
import java.net.URL

/**
 * Canonical stable path for thumbnail reload maps and server URL resolution.
 *
 * Glide video disk cache keys intentionally keep the legacy percent-encoded path from
 * [legacyPathFromServeUrl] so existing on-disk thumbnails survive app updates. Reload
 * epoch / preferExo maps use [canonicalFromServeUrl] (decoded), matching
 * [ThumbnailCacheIdentity.stableServePath].
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

    /** Legacy Glide disk-cache identity (percent-encoded path segments when present). */
    @JvmStatic
    fun legacyPathFromServeUrl(url: String): String {
        return try {
            val path = URL(url).path ?: return url
            if (path.isEmpty()) {
                return url
            }
            val secondSlash = path.indexOf('/', 1)
            if (secondSlash > 0) path.substring(secondSlash) else path
        } catch (_: MalformedURLException) {
            url
        }
    }

    /** Decoded path aligned with explorer file paths and reload maps. */
    @JvmStatic
    fun canonicalFromServeUrl(url: String): String = normalize(legacyPathFromServeUrl(url))

    @JvmStatic
    fun fromServeUrl(url: String): String = canonicalFromServeUrl(url)
}
