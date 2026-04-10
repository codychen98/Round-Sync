package ca.pkay.rcloneexplorer.util

enum class VideoDoubleTapHorizontalZone {
    LEFT,
    CENTER,
    RIGHT,
}

object VideoPlayerDoubleTapZones {

    fun horizontalZone(x: Float, widthPx: Int): VideoDoubleTapHorizontalZone {
        val w = widthPx.coerceAtLeast(1)
        val third = w / 3f
        return when {
            x < third -> VideoDoubleTapHorizontalZone.LEFT
            x < third * 2f -> VideoDoubleTapHorizontalZone.CENTER
            else -> VideoDoubleTapHorizontalZone.RIGHT
        }
    }
}
