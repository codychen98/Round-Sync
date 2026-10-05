package ca.pkay.rcloneexplorer.Glide

/**
 * Background prefetch gives up when MediaMetadataRetriever reports no frame well under a second.
 * The six ExoPlayer seeks that follow that result take about 12s each and, for a file that
 * already failed this fast, do not produce a frame. The failure is stored as a tiny JPEG under
 * the video's existing disk-cache key so the next probe is a hit and Glide can decode it
 * without starting another extract.
 */
object VideoThumbnailFastNoFrame {

    /**
     * Device logs for an unreadable MOV report `mmrMs` around 60. Anything faster than this
     * during prefetch is treated as a terminal miss; a slower MMR miss still falls through to Exo.
     */
    const val FAST_NO_FRAME_MAX_MS = 500L

    @JvmStatic
    fun shouldSkipExoSeeks(
        mmrReturnedNoFrame: Boolean,
        mmrMs: Long,
        backgroundPrefetch: Boolean,
        preferExo: Boolean,
    ): Boolean {
        if (!mmrReturnedNoFrame || !backgroundPrefetch || preferExo) {
            return false
        }
        return mmrMs in 0 until FAST_NO_FRAME_MAX_MS
    }

    /**
     * 1x1 JPEG. Declared before [MARKER] because object initializers run in source order,
     * and a later const is not yet available to an earlier property.
     */
    private const val MARKER_HEX =
        "ffd8ffe000104a46494600010100000100010000ffdb004300281c1e231e19282321232d2b28303c64413c37373c7b585d4964918099968f808c8aa0b4e6c3a0aadaad8a8cc8ffcbdaeef5ffffff9bc1fffffffaffe6fdfff8ffdb0043012b2d2d3c353c76414176f8a58ca5f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8f8ffc00011080001000103012200021101031101ffc4001500010100000000000000000000000000000005ffc40014100100000000000000000000000000000000ffc40014010100000000000000000000000000000000ffc40014110100000000000000000000000000000000ffda000c03010002110311003f009a003fffd9"

    private val MARKER: ByteArray = decodeHex(MARKER_HEX)

    /** Decodable JPEG written under the video disk key when prefetch gives up. */
    @JvmStatic
    fun markerJpeg(): ByteArray = MARKER.copyOf()

    private fun decodeHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToInt(16)
            val lo = hex[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) + lo).toByte()
        }
        return out
    }
}
