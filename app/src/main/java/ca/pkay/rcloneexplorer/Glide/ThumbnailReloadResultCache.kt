package ca.pkay.rcloneexplorer.Glide

import java.util.concurrent.ConcurrentHashMap

/**
 * Session JPEG payloads produced by user reload. Glide fetcher serves these before re-extracting.
 */
object ThumbnailReloadResultCache {

    private val jpegByStablePath = ConcurrentHashMap<String, ByteArray>()

    @JvmStatic
    fun put(stablePath: String, jpegBytes: ByteArray) {
        jpegByStablePath[key(stablePath)] = jpegBytes.copyOf()
    }

    @JvmStatic
    fun remove(stablePath: String) {
        jpegByStablePath.remove(key(stablePath))
    }

    @JvmStatic
    fun get(stablePath: String): ByteArray? = jpegByStablePath[key(stablePath)]

    private fun key(stablePath: String): String = ThumbnailStablePath.normalize(stablePath)
}
