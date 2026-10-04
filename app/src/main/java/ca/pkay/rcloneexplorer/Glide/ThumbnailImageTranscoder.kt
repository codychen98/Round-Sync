package ca.pkay.rcloneexplorer.Glide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Shrinks a downloaded image to a bounded JPEG before Glide writes it to the DATA disk cache.
 *
 * Without this, `DiskCacheStrategy.DATA` stores the full-resolution original (several MB per
 * photo), which overflows the thumbnail cache and evicts restored entries. Results:
 * - unknown / non-raster payloads (SVG, HTML, text): `null`, so the load fails and nothing is cached;
 * - GIF and animated WebP: original bytes, to keep animation;
 * - rasters already within [maxEdgePx]: original bytes;
 * - larger rasters: EXIF-oriented, downscaled JPEG.
 * Decode failures of a recognised raster return the original bytes so behaviour matches today.
 */
object ThumbnailImageTranscoder {

    const val DEFAULT_MAX_EDGE_PX = 1280
    private const val JPEG_QUALITY = 85

    enum class Format { JPEG, PNG, WEBP, WEBP_ANIMATED, GIF, HEIF, BMP, UNKNOWN }

    @JvmStatic
    fun sniff(bytes: ByteArray): Format {
        if (bytes.size < 12) {
            return Format.UNKNOWN
        }
        return when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> Format.JPEG
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Format.PNG
            bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> Format.GIF
            bytes.startsWithAscii("RIFF") && bytes.asciiAt(8, "WEBP") -> sniffWebp(bytes)
            bytes.asciiAt(4, "ftyp") && isHeifBrand(bytes) -> Format.HEIF
            bytes.startsWithAscii("BM") -> Format.BMP
            else -> Format.UNKNOWN
        }
    }

    @JvmStatic
    @JvmOverloads
    fun transcode(bytes: ByteArray, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): ByteArray? {
        return when (sniff(bytes)) {
            Format.UNKNOWN -> null
            Format.GIF, Format.WEBP_ANIMATED -> bytes
            else -> try {
                downscaleOrPassthrough(bytes, maxEdgePx.coerceAtLeast(1))
            } catch (_: Exception) {
                bytes
            } catch (_: OutOfMemoryError) {
                bytes
            }
        }
    }

    private fun downscaleOrPassthrough(bytes: ByteArray, maxEdgePx: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcEdge <= 0 || srcEdge <= maxEdgePx) {
            return bytes
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(srcEdge, maxEdgePx) }
        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
        try {
            val matrix = Matrix()
            val scale = maxEdgePx.toFloat() / maxOf(sampled.width, sampled.height)
            if (scale < 1f) {
                matrix.postScale(scale, scale)
            }
            applyExifOrientation(matrix, readOrientation(bytes))
            val oriented = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
            try {
                return encodeJpeg(oriented)
            } finally {
                if (oriented !== sampled) {
                    oriented.recycle()
                }
            }
        } finally {
            sampled.recycle()
        }
    }

    /** Largest power of two that keeps the sampled edge at or above the target (no upscaling later). */
    internal fun sampleSizeFor(srcEdge: Int, maxEdgePx: Int): Int {
        var sample = 1
        while (srcEdge / (sample * 2) >= maxEdgePx) {
            sample *= 2
        }
        return sample
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun applyExifOrientation(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> Unit
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun sniffWebp(bytes: ByteArray): Format {
        // VP8X extended header: flags byte at offset 20, animation bit 0x02.
        val animated = bytes.asciiAt(12, "VP8X") && bytes.size > 20 && (bytes[20].toInt() and 0x02) != 0
        return if (animated) Format.WEBP_ANIMATED else Format.WEBP
    }

    private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis")

    private fun isHeifBrand(bytes: ByteArray): Boolean =
        String(bytes, 8, 4, Charsets.US_ASCII) in heifBrands

    private fun ByteArray.startsWith(vararg magic: Int): Boolean =
        magic.indices.all { (this[it].toInt() and 0xFF) == magic[it] }

    private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

    private fun ByteArray.asciiAt(offset: Int, text: String): Boolean {
        if (size < offset + text.length) {
            return false
        }
        return text.indices.all { this[offset + it] == text[it].code.toByte() }
    }
}
