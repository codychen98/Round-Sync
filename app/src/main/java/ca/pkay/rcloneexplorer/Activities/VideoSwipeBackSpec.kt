package ca.pkay.rcloneexplorer.Activities

import android.content.res.Resources
import android.util.TypedValue

/**
 * Thresholds for vertical swipe-dismiss (navigate back) on fullscreen video playback.
 *
 * **Precedence:** Dismiss logic must respect this contract before finishing the activity or equivalent:
 * swipe-back is triggered only via fling semantics when [SWIPE_BACK_REQUIRES_FLING] is true; after a
 * `GestureDetector`/`GestureDetectorCompat` **onFling** callback, never from a slow drag with no fling velocity.
 *
 * **Manual QA / tuning:** test checklist lives in repo `Roadmap/vertical_swipe_back.md`; adjust constants there
 * if scrubbing or double-tap seek regress.
 */
object VideoSwipeBackSpec {

    /**
     * When true, dismissal is gated on **fling-only** callbacks (recommended: fewer accidental exits while scrolling UI).
     */
    const val SWIPE_BACK_REQUIRES_FLING: Boolean = true

    /**
     * Minimum absolute vertical fling velocity in **pixels per second** (`velocityY` from `onFling`).
     * Higher values reduce accidental dismiss; tune on device alongside [MIN_VERTICAL_TRAVEL_DP].
     */
    const val MIN_ABS_VELOCITY_Y_PX_S: Int = 1200

    /**
     * Minimum vertical travel expected for a qualifying gesture, in **density-independent pixels**.
     * Step 2 may compare against cumulative `dy` from the tracked pointer sequence together with velocity.
     */
    const val MIN_VERTICAL_TRAVEL_DP: Float = 56f

    /**
     * Vertical motion must dominate horizontal: require `abs(dy) > VERTICAL_DOMINANCE_RATIO * abs(dx)`
     * (using fling deltas from `onFling` or a velocity tracker sequence, per implementation).
     */
    const val VERTICAL_DOMINANCE_RATIO: Float = 2f

    /**
     * When the playback controller overlay is visible, vertical swipe-dismiss is ignored for touch-down points
     * in the bottom `[0, fraction]` of the **player view height** (`0..1`). Tune after manual QA (e.g. `0.18f`
     * exposes more swipe-dismiss area above scrubber; larger values reduce accidental exits near timeline).
     */
    const val CONTROLLER_VISIBLE_BOTTOM_EXCLUSION_FRACTION: Float = 0.22f

    fun minVerticalTravelPx(resources: Resources): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            MIN_VERTICAL_TRAVEL_DP,
            resources.displayMetrics
        )
}
