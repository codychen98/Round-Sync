package ca.pkay.rcloneexplorer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream

/**
 * Loads a bitmap from a local rclone HTTP serve URL using OkHttp (so an OkHttp [Cache] applies).
 */
object ImageServeBitmapLoader {

    private const val MARK_READ_LIMIT_BYTES = 1024 * 1024

    fun loadSampled(httpUrl: String, client: OkHttpClient, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
        return try {
            loadSampledInternal(httpUrl, client, maxWidthPx, maxHeightPx)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            // A failed decode must never take down the process; the caller shows a
            // placeholder instead.
            null
        }
    }

    private fun loadSampledInternal(
        httpUrl: String,
        client: OkHttpClient,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Bitmap? {
        val reqW = maxWidthPx.coerceAtLeast(1)
        val reqH = maxHeightPx.coerceAtLeast(1)
        val request = Request.Builder().url(httpUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            val body = response.body ?: return null
            body.byteStream().use { raw ->
                val stream = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, MARK_READ_LIMIT_BYTES)
                stream.mark(MARK_READ_LIMIT_BYTES)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                stream.reset()
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = calculateInSampleSize(bounds, reqW, reqH)
                }
                return BitmapFactory.decodeStream(stream, null, opts)
            }
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)

    /**
     * Power-of-two sample size that bounds the decoded dimensions at or below the requested
     * bounds. The previous "keep both dimensions >= requested" variant decoded 26 MP camera
     * originals at full resolution (~104 MB each) under the 4096 px high-fidelity bound,
     * which OOM-crashed the viewer when the pager preloaded neighboring pages.
     */
    internal fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }
        val boundW = reqWidth.coerceAtLeast(1)
        val boundH = reqHeight.coerceAtLeast(1)
        var inSampleSize = 1
        while (width / inSampleSize > boundW || height / inSampleSize > boundH) {
            inSampleSize *= 2
        }
        return inSampleSize
    }
}
