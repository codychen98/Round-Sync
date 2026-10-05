package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchTallyTest {

    @Test
    fun mixedSuccessAndFailure_countsOnlySuccessesAsLoaded() {
        val tally = PrefetchTally(loaded = 12)
            .plusSuccess()
            .plusFailure()
            .plusSuccess()
            .plusFailure()
            .plusFailure()

        assertEquals(14, tally.loaded)
        assertEquals(3, tally.failed)
        assertEquals(17, tally.processed)
    }

    @Test
    fun outcomeOf_carriesTallyAndFlags() {
        val tally = PrefetchTally(loaded = 5, failed = 2)
        val outcome = ThumbnailPrefetchExecutor.outcomeOf(tally, total = 10, stoppedEarly = true)

        assertEquals(5, outcome.loaded)
        assertEquals(2, outcome.failed)
        assertEquals(10, outcome.total)
        assertEquals(true, outcome.stoppedEarly)
    }

    @Test
    fun plusOperations_doNotMutateOriginal() {
        val original = PrefetchTally(loaded = 1)
        original.plusSuccess()
        original.plusFailure()
        assertEquals(PrefetchTally(loaded = 1, failed = 0), original)
    }
}
