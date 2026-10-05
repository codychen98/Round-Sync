package ca.pkay.rcloneexplorer.util

/**
 * Immutable success / failure counters for one folder prefetch. [loaded] starts at the number
 * of items already on disk and only grows on a successful Glide load, so progress never counts
 * timeouts or failed fetches as cached thumbnails.
 */
data class PrefetchTally(
    val loaded: Int,
    val failed: Int = 0,
) {
    fun plusSuccess(): PrefetchTally = copy(loaded = loaded + 1)

    fun plusFailure(): PrefetchTally = copy(failed = failed + 1)

    /** Items processed so far (cached + fetched + failed); drives progress UI monotonically. */
    val processed: Int
        get() = loaded + failed
}
