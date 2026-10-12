package ca.pkay.rcloneexplorer.util

/**
 * Pure helpers for in-app video seek decisions (unit-tested without ExoPlayer).
 */
object VideoSeekPolicy {

    /** True when relative seek / scrub is safe for the current media item. */
    @JvmStatic
    fun canSeekInCurrentWindow(isCurrentMediaItemSeekable: Boolean, hasSeekInCurrentWindowCommand: Boolean): Boolean =
        isCurrentMediaItemSeekable && hasSeekInCurrentWindowCommand
}
