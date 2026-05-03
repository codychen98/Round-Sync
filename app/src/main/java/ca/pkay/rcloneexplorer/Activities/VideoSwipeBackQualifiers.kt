package ca.pkay.rcloneexplorer.Activities

import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Shared vertical swipe-back rules for fullscreen surfaces that use [VideoSwipeBackSpec].
 */
object VideoSwipeBackQualifiers {

    fun rawMotionHitsView(ev: MotionEvent, v: View): Boolean {
        if (v.visibility != View.VISIBLE) {
            return false
        }
        if (v.width <= 0 || v.height <= 0) {
            return false
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val rx = ev.rawX
        val ry = ev.rawY
        return rx >= loc[0] &&
            rx <= loc[0] + v.width &&
            ry >= loc[1] &&
            ry <= loc[1] + v.height
    }

    fun qualifiesVerticalSwipeBackDismiss(
        resources: Resources,
        start: MotionEvent,
        end: MotionEvent,
        velocityY: Float,
        isStartExcluded: (MotionEvent) -> Boolean,
    ): Boolean {
        if (!VideoSwipeBackSpec.SWIPE_BACK_REQUIRES_FLING) {
            return false
        }
        if (isStartExcluded(start)) {
            return false
        }
        val dx = end.rawX - start.rawX
        val dy = end.rawY - start.rawY
        val minTravel = VideoSwipeBackSpec.minVerticalTravelPx(resources)
        if (abs(dy) < minTravel) {
            return false
        }
        if (abs(dy) <= VideoSwipeBackSpec.VERTICAL_DOMINANCE_RATIO * abs(dx)) {
            return false
        }
        return abs(velocityY) >= VideoSwipeBackSpec.MIN_ABS_VELOCITY_Y_PX_S
    }
}
