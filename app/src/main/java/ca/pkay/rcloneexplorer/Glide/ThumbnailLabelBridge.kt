package ca.pkay.rcloneexplorer.Glide

/**
 * Exposes [ReadableCacheKey.fromStablePath] to other packages (lifecycle purge/carry).
 */
object ThumbnailLabelBridge {
    @JvmStatic
    fun fromStablePath(stablePath: String, namespace: String): String =
        ReadableCacheKey.fromStablePath(stablePath, namespace)
}
