package ca.pkay.rcloneexplorer.Activities

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root for fullscreen video: observes [MotionEvent]s on the overlay area that belong to the center
 * (not handled by sibling strips). Once vertical motion dominates beyond touch slop, intercepts
 * the remainder of the gesture and evaluates a swipe-back dismissal on [MotionEvent.ACTION_UP].
 */
class VideoSwipeBackInterceptFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Optional; null disables center swipe observation. */
    var swipeDismissDelegate: SwipeDismissDelegate? = null

    interface SwipeDismissDelegate {

        /** True only for touches whose DOWN should compete for vertical swipe-back (typically player center gap). */
        fun shouldObserveCenterSwipeFromDown(ev: MotionEvent): Boolean

        /** Apply [VideoSwipeBackSpec] thresholds; finish activity when appropriate. Return true when handled as dismiss. */
        fun maybeFinishFromSwipeGesture(
            down: MotionEvent,
            up: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean
    }

    private var swipeObservingCenter = false
    private var swipeIntercepted = false
    private var swipeDown: MotionEvent? = null
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (swipeIntercepted) {
            return true
        }
        val del = swipeDismissDelegate ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                endSwipeTracking()
                swipeObservingCenter = del.shouldObserveCenterSwipeFromDown(ev)
                if (!swipeObservingCenter) return false
                swipeDown = MotionEvent.obtain(ev)
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val down = swipeDown
                if (!swipeObservingCenter || down == null) return false
                velocityTracker!!.addMovement(ev)
                val dx = ev.x - down.x
                val dy = ev.y - down.y
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(dy) > slop &&
                    abs(dy) > VideoSwipeBackSpec.VERTICAL_DOMINANCE_RATIO * abs(dx)
                ) {
                    swipeIntercepted = true
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endSwipeTracking()
                return false
            }
            else -> return false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val del = swipeDismissDelegate ?: return super.onTouchEvent(event)
        if (!swipeIntercepted) return super.onTouchEvent(event)

        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val down = swipeDown
                velocityTracker!!.computeCurrentVelocity(1000)
                val vx = velocityTracker!!.xVelocity
                val vy = velocityTracker!!.yVelocity
                if (down != null) {
                    del.maybeFinishFromSwipeGesture(down, event, vx, vy)
                }
                endSwipeTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                endSwipeTracking()
            }
        }
        return true
    }

    private fun endSwipeTracking() {
        swipeIntercepted = false
        swipeObservingCenter = false
        swipeDown?.recycle()
        swipeDown = null
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        endSwipeTracking()
        super.onDetachedFromWindow()
    }
}
