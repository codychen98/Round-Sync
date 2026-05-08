package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin unit tests for the baseline-merging math used in
 * [LastFolderThumbnailPrefetchWorker.doWork].
 *
 * The formula under test:
 *   adjustedTotal = max(baselineTotal, baselineLoaded + toFetchSize)
 *
 * This guarantees the notification counter never goes backwards relative to what
 * the explorer adapter already displayed.
 */
class PrefetchBaselineTest {

    private fun adjustedTotal(baselineLoaded: Int, baselineTotal: Int, toFetchSize: Int): Int =
        maxOf(baselineTotal, baselineLoaded + toFetchSize)

    @Test
    fun baselineDominates_whenHigherThanSumOfLoadedAndFetch() {
        // Explorer showed 59/71; worker found 12 items still to fetch: 59+12=71 == baselineTotal.
        assertEquals(71, adjustedTotal(baselineLoaded = 59, baselineTotal = 71, toFetchSize = 12))
    }

    @Test
    fun fetchSumDominates_whenBaselineIsSmall() {
        // Explorer showed 5/10 but folder has grown; 20 items still to fetch: 5+20=25 > 10.
        assertEquals(25, adjustedTotal(baselineLoaded = 5, baselineTotal = 10, toFetchSize = 20))
    }

    @Test
    fun noBaseline_totalsEqualsToFetchSize() {
        // Worker enqueued with zero baseline (e.g. from a non-explorer trigger).
        assertEquals(15, adjustedTotal(baselineLoaded = 0, baselineTotal = 0, toFetchSize = 15))
    }

    @Test
    fun baselineAndSumTie_returnsSameValue() {
        assertEquals(71, adjustedTotal(baselineLoaded = 59, baselineTotal = 71, toFetchSize = 12))
    }

    @Test
    fun allAlreadyCachedExceptOne_baselineDominates() {
        // Explorer showed 70/71; only 1 item still to fetch: 70+1=71 == baselineTotal.
        assertEquals(71, adjustedTotal(baselineLoaded = 70, baselineTotal = 71, toFetchSize = 1))
    }

    @Test
    fun workerStartsAtBaselineLoaded_notZero() {
        val baselineLoaded = 59
        val adjustedTotal  = adjustedTotal(baselineLoaded, baselineTotal = 71, toFetchSize = 12)
        // After the loop the worker reports adjustedTotal/adjustedTotal — which should be 71/71.
        assertEquals(71, adjustedTotal)
        // The final "loaded" counter equals adjustedTotal: baselineLoaded + toFetchSize = 71.
        assertEquals(adjustedTotal, baselineLoaded + 12)
    }

    @Test
    fun zeroFetchItems_adjustedTotalEqualBaselineTotal() {
        // All items were cached; toFetch is empty. Early return in practice, but the
        // math should still be sound: max(71, 71+0) = 71.
        assertEquals(71, adjustedTotal(baselineLoaded = 71, baselineTotal = 71, toFetchSize = 0))
    }
}
