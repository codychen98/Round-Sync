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
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        if (height <= 0 || width <= 0) {
            return 1
        }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
