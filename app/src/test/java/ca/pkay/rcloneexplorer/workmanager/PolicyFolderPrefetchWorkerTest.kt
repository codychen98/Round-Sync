package ca.pkay.rcloneexplorer.workmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin unit tests for the cache skip-rule math in [PolicyFolderPrefetchWorker.doCacheWork].
 *
 * The two rules under test:
 *   1. Skip-oversized: skip if fileSize > exoMax / 2
 *   2. Stop-quota: stop accumulating if runningBytesAdded + fileSize > remainingQuota
 *
 * Tests also cover [PolicyFolderPrefetchWorker.uniqueWorkName] to confirm stable hex encoding
 * across multiple sessions.
 */
class PolicyFolderPrefetchWorkerTest {

    // region — skip-oversized rule

    private fun isOversized(fileSize: Long, exoMax: Long): Boolean = fileSize > exoMax / 2L

    @Test
    fun oversized_fileExactlyHalfOfExoMax_notOversized() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        assertFalse(isOversized(exoMax / 2L, exoMax))
    }

    @Test
    fun oversized_fileOneByteAboveHalf_isOversized() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        assertTrue(isOversized(exoMax / 2L + 1L, exoMax))
    }

    @Test
    fun oversized_smallFile_notOversized() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        assertFalse(isOversized(32L * 1024L * 1024L, exoMax))
    }

    @Test
    fun oversized_zeroFile_notOversized() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        assertFalse(isOversized(0L, exoMax))
    }

    // endregion

    // region — stop-quota rule

    private fun exceedsRemainingQuota(
        runningBytesAdded: Long,
        fileSize: Long,
        remainingQuota: Long,
    ): Boolean = runningBytesAdded + fileSize > remainingQuota

    @Test
    fun quota_firstFileFitsExactly_noStop() {
        val remainingQuota = 100L * 1024L * 1024L
        assertFalse(exceedsRemainingQuota(0L, remainingQuota, remainingQuota))
    }

    @Test
    fun quota_firstFileOneByteOverQuota_stops() {
        val remainingQuota = 100L * 1024L * 1024L
        assertTrue(exceedsRemainingQuota(0L, remainingQuota + 1L, remainingQuota))
    }

    @Test
    fun quota_runningTotalPlusNextFileTips_stops() {
        val remainingQuota = 200L * 1024L * 1024L
        val alreadyAdded = 150L * 1024L * 1024L
        val nextFile = 100L * 1024L * 1024L
        assertTrue(exceedsRemainingQuota(alreadyAdded, nextFile, remainingQuota))
    }

    @Test
    fun quota_runningTotalPlusNextFileExact_noStop() {
        val remainingQuota = 200L * 1024L * 1024L
        val alreadyAdded = 100L * 1024L * 1024L
        val nextFile = 100L * 1024L * 1024L
        assertFalse(exceedsRemainingQuota(alreadyAdded, nextFile, remainingQuota))
    }

    @Test
    fun quota_zeroQuota_anyFileStops() {
        assertTrue(exceedsRemainingQuota(0L, 1L, 0L))
    }

    // endregion

    // region — combined: simulated CACHE loop decisions

    data class VideoEntry(val name: String, val size: Long)

    /**
     * Runs the same accept/skip/stop logic as [PolicyFolderPrefetchWorker.doCacheWork] on a
     * synthetic list. Returns (accepted, skippedOversized, stopped) counts.
     */
    private fun simulateCacheLoop(
        items: List<VideoEntry>,
        exoMax: Long,
        remainingQuota: Long,
    ): Triple<Int, Int, Boolean> {
        var accepted = 0
        var skippedOversized = 0
        var stopped = false
        var runningBytesAdded = 0L
        for (item in items) {
            if (item.size > exoMax / 2L) {
                skippedOversized++
                continue
            }
            if (runningBytesAdded + item.size > remainingQuota) {
                stopped = true
                break
            }
            accepted++
            runningBytesAdded += item.size
        }
        return Triple(accepted, skippedOversized, stopped)
    }

    @Test
    fun cacheLoop_allFilesSmall_allAccepted() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        val quota = 1L * 1024L * 1024L * 1024L
        val items = listOf(
            VideoEntry("a.mkv", 100L * 1024L * 1024L),
            VideoEntry("b.mkv", 200L * 1024L * 1024L),
            VideoEntry("c.mkv", 300L * 1024L * 1024L),
        )
        val (accepted, skipped, stopped) = simulateCacheLoop(items, exoMax, quota)
        assertEquals(3, accepted)
        assertEquals(0, skipped)
        assertFalse(stopped)
    }

    @Test
    fun cacheLoop_oneOversizedMixedWithSmall_oversizedSkipped() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        val quota = 9L * 1024L * 1024L * 1024L
        val items = listOf(
            VideoEntry("small.mkv", 200L * 1024L * 1024L),
            VideoEntry("huge.mkv", 6L * 1024L * 1024L * 1024L), // > exoMax/2 (5 GiB)
            VideoEntry("small2.mkv", 100L * 1024L * 1024L),
        )
        val (accepted, skipped, stopped) = simulateCacheLoop(items, exoMax, quota)
        assertEquals(2, accepted)
        assertEquals(1, skipped)
        assertFalse(stopped)
    }

    @Test
    fun cacheLoop_quotaExhaustedMidList_stopsEarly() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        val quota = 250L * 1024L * 1024L
        val items = listOf(
            VideoEntry("a.mkv", 100L * 1024L * 1024L),
            VideoEntry("b.mkv", 100L * 1024L * 1024L),
            VideoEntry("c.mkv", 200L * 1024L * 1024L), // running=200 + 200 > 250 → stop
        )
        val (accepted, skipped, stopped) = simulateCacheLoop(items, exoMax, quota)
        assertEquals(2, accepted)
        assertEquals(0, skipped)
        assertTrue(stopped)
    }

    @Test
    fun cacheLoop_noRemainingQuota_stopImmediately() {
        val exoMax = 10L * 1024L * 1024L * 1024L
        val items = listOf(VideoEntry("a.mkv", 100L * 1024L * 1024L))
        val (accepted, _, stopped) = simulateCacheLoop(items, exoMax, remainingQuota = 0L)
        assertEquals(0, accepted)
        assertTrue(stopped)
    }

    @Test
    fun cacheLoop_emptyList_nothingAccepted() {
        val (accepted, skipped, stopped) = simulateCacheLoop(
            emptyList(), 10L * 1024L * 1024L * 1024L, 5L * 1024L * 1024L * 1024L,
        )
        assertEquals(0, accepted)
        assertEquals(0, skipped)
        assertFalse(stopped)
    }

    // endregion

    // region — uniqueWorkName encoding

    @Test
    fun uniqueWorkName_thumbnail_prefixAndEncoding() {
        val name = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "myRemote", "/Video Archive",
        )
        assertTrue(name.startsWith("policy_prefetch_thumb_"))
    }

    @Test
    fun uniqueWorkName_cache_prefixAndEncoding() {
        val name = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_CACHE, "myRemote", "/Video Archive",
        )
        assertTrue(name.startsWith("policy_prefetch_cache_"))
    }

    @Test
    fun uniqueWorkName_sameInputsSameOutput() {
        val a = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remote", "/path/to/folder",
        )
        val b = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remote", "/path/to/folder",
        )
        assertEquals(a, b)
    }

    @Test
    fun uniqueWorkName_differentFoldersDifferentNames() {
        val a = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remote", "/folder/a",
        )
        val b = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remote", "/folder/b",
        )
        assertTrue(a != b)
    }

    @Test
    fun uniqueWorkName_differentRemotesDifferentNames() {
        val a = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remoteA", "/folder",
        )
        val b = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remoteB", "/folder",
        )
        assertTrue(a != b)
    }

    @Test
    fun uniqueWorkName_onlyHexAndUnderscoresAfterPrefix() {
        val name = PolicyFolderPrefetchWorker.uniqueWorkName(
            PolicyFolderPrefetchWorker.TYPE_THUMBNAIL, "remote with spaces", "/path/to folder",
        )
        val suffix = name.removePrefix("policy_prefetch_thumb_")
        assertTrue(
            "Suffix must contain only hex digits and underscores: $suffix",
            suffix.all { it in '0'..'9' || it in 'a'..'f' || it == '_' },
        )
    }

    // endregion
}
