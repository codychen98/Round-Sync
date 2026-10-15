package ca.pkay.rcloneexplorer.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.VisibleForTesting
import kotlin.math.max

/**
 * Composes 1–4 cell bitmaps into a single folder-tile collage.
 *
 * - 1: full bleed
 * - 2: side-by-side rectangles
 * - 3: two on top, one wide bottom
 * - 4: 2×2 equal squares
 */
object FolderCollageComposer {

    const val DEFAULT_SIZE_PX = 512
    private const val GUTTER_PX = 4
    private const val BADGE_FRACTION = 0.20f
    private const val BADGE_PAD_FRACTION = 0.04f

    @JvmStatic
    @JvmOverloads
    fun compose(
        cells: List<Bitmap>,
        badge: Drawable?,
        sizePx: Int = DEFAULT_SIZE_PX,
    ): Bitmap {
        val usable = cells.filter { !it.isRecycled && it.width > 0 && it.height > 0 }.take(4)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(0x22, 0x22, 0x22))
        if (usable.isEmpty()) {
            drawBadge(canvas, badge, sizePx)
            return out
        }
        val gutter = max(1, (sizePx * GUTTER_PX) / DEFAULT_SIZE_PX)
        when (usable.size) {
            1 -> drawCell(canvas, usable[0], Rect(0, 0, sizePx, sizePx))
            2 -> {
                val mid = (sizePx - gutter) / 2
                drawCell(canvas, usable[0], Rect(0, 0, mid, sizePx))
                drawCell(canvas, usable[1], Rect(mid + gutter, 0, sizePx, sizePx))
            }
            3 -> {
                val midX = (sizePx - gutter) / 2
                val midY = (sizePx - gutter) / 2
                drawCell(canvas, usable[0], Rect(0, 0, midX, midY))
                drawCell(canvas, usable[1], Rect(midX + gutter, 0, sizePx, midY))
                drawCell(canvas, usable[2], Rect(0, midY + gutter, sizePx, sizePx))
            }
            else -> {
                val midX = (sizePx - gutter) / 2
                val midY = (sizePx - gutter) / 2
                drawCell(canvas, usable[0], Rect(0, 0, midX, midY))
                drawCell(canvas, usable[1], Rect(midX + gutter, 0, sizePx, midY))
                drawCell(canvas, usable[2], Rect(0, midY + gutter, midX, sizePx))
                drawCell(canvas, usable[3], Rect(midX + gutter, midY + gutter, sizePx, sizePx))
            }
        }
        drawBadge(canvas, badge, sizePx)
        return out
    }

    @VisibleForTesting
    internal fun drawCell(canvas: Canvas, src: Bitmap, dst: Rect) {
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

    private fun drawBadge(canvas: Canvas, badge: Drawable?, sizePx: Int) {
        if (badge == null) {
            return
        }
        val pad = (sizePx * BADGE_PAD_FRACTION).toInt().coerceAtLeast(2)
        val badgeSize = (sizePx * BADGE_FRACTION).toInt().coerceAtLeast(16)
        val left = pad
        val top = sizePx - pad - badgeSize
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0xB0, 0x10, 0x10, 0x10)
        }
        val radius = badgeSize * 0.22f
        canvas.drawRoundRect(
            RectF(
                left.toFloat() - pad * 0.35f,
                top.toFloat() - pad * 0.35f,
                (left + badgeSize + pad * 0.35f),
                (top + badgeSize + pad * 0.35f),
            ),
            radius,
            radius,
            bgPaint,
        )
        val mut = badge.mutate()
        mut.setBounds(left, top, left + badgeSize, top + badgeSize)
        mut.alpha = 230
        mut.draw(canvas)
    }
}
