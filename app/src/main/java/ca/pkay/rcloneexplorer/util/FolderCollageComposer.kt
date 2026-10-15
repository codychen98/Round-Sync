package ca.pkay.rcloneexplorer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.annotation.VisibleForTesting
import kotlin.math.max

/**
 * Composes 1–4 cell bitmaps into a single folder-tile collage with outer container inset
 * (so the tile reads as a folder preview, not a full-bleed file thumb).
 *
 * - 1: single cell inset
 * - 2: side-by-side rectangles
 * - 3: two on top, one wide bottom
 * - 4: 2×2 equal squares
 */
object FolderCollageComposer {

    const val DEFAULT_SIZE_PX = 512
    /** Outer margin as fraction of tile size (container padding). */
    private const val OUTER_INSET_FRACTION = 0.08f
    private const val GUTTER_PX = 4

    @JvmStatic
    @JvmOverloads
    fun compose(
        cells: List<Bitmap>,
        sizePx: Int = DEFAULT_SIZE_PX,
    ): Bitmap {
        val usable = cells.filter { !it.isRecycled && it.width > 0 && it.height > 0 }.take(4)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Container background (matches dark tile chrome).
        canvas.drawColor(Color.rgb(0x2A, 0x2A, 0x2E))
        if (usable.isEmpty()) {
            return out
        }
        val inset = max(2, (sizePx * OUTER_INSET_FRACTION).toInt())
        val content = Rect(inset, inset, sizePx - inset, sizePx - inset)
        val gutter = max(1, (sizePx * GUTTER_PX) / DEFAULT_SIZE_PX)
        when (usable.size) {
            1 -> drawCell(canvas, usable[0], content)
            2 -> {
                val mid = content.left + (content.width() - gutter) / 2
                drawCell(canvas, usable[0], Rect(content.left, content.top, mid, content.bottom))
                drawCell(
                    canvas,
                    usable[1],
                    Rect(mid + gutter, content.top, content.right, content.bottom),
                )
            }
            3 -> {
                val midX = content.left + (content.width() - gutter) / 2
                val midY = content.top + (content.height() - gutter) / 2
                drawCell(canvas, usable[0], Rect(content.left, content.top, midX, midY))
                drawCell(canvas, usable[1], Rect(midX + gutter, content.top, content.right, midY))
                drawCell(
                    canvas,
                    usable[2],
                    Rect(content.left, midY + gutter, content.right, content.bottom),
                )
            }
            else -> {
                val midX = content.left + (content.width() - gutter) / 2
                val midY = content.top + (content.height() - gutter) / 2
                drawCell(canvas, usable[0], Rect(content.left, content.top, midX, midY))
                drawCell(canvas, usable[1], Rect(midX + gutter, content.top, content.right, midY))
                drawCell(canvas, usable[2], Rect(content.left, midY + gutter, midX, content.bottom))
                drawCell(
                    canvas,
                    usable[3],
                    Rect(midX + gutter, midY + gutter, content.right, content.bottom),
                )
            }
        }
        return out
    }

    @VisibleForTesting
    internal fun drawCell(canvas: Canvas, src: Bitmap, dst: Rect) {
        if (dst.width() <= 0 || dst.height() <= 0) {
            return
        }
        val srcRect = centerCropSrc(src.width, src.height, dst.width(), dst.height())
        canvas.drawBitmap(src, srcRect, dst, null)
    }

    private fun centerCropSrc(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return Rect(0, 0, max(srcW, 1), max(srcH, 1))
        }
        val srcAspect = srcW.toFloat() / srcH
        val dstAspect = dstW.toFloat() / dstH
        return if (srcAspect > dstAspect) {
            val newW = (srcH * dstAspect).toInt().coerceAtLeast(1)
            val left = (srcW - newW) / 2
            Rect(left, 0, left + newW, srcH)
        } else {
            val newH = (srcW / dstAspect).toInt().coerceAtLeast(1)
            val top = (srcH - newH) / 2
            Rect(0, top, srcW, top + newH)
        }
    }
}
