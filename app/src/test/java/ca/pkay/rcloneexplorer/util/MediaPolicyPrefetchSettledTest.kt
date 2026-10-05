package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPolicyPrefetchSettledTest {

    private val ttl = MediaPolicyPrefetchSettled.SETTLE_TTL_MS
    private val now = 1_000_000L

    @Test
    fun shouldEnqueueOnStart_whenNeverSettledOrHashEmpty() {
        assertTrue(MediaPolicyPrefetchSettled.shouldEnqueueOnStart(0L, now, "abc", "abc", ttl))
        assertTrue(MediaPolicyPrefetchSettled.shouldEnqueueOnStart(now, now, "abc", "", ttl))
    }

    @Test
    fun shouldEnqueueOnStart_skipsWhenSameHashWithinTtl() {
        assertFalse(
            MediaPolicyPrefetchSettled.shouldEnqueueOnStart(
                settledAt = now - 60_000L,
                now = now,
                policyHash = "abc",
                storedHash = "abc",
                ttlMs = ttl,
            ),
        )
    }

    @Test
    fun shouldEnqueueOnStart_whenTtlExpiredOrPolicyHashChanged() {
        assertTrue(
            MediaPolicyPrefetchSettled.shouldEnqueueOnStart(
                settledAt = now - ttl,
                now = now,
                policyHash = "abc",
                storedHash = "abc",
                ttlMs = ttl,
            ),
        )
        assertTrue(
            MediaPolicyPrefetchSettled.shouldEnqueueOnStart(
                settledAt = now - 1_000L,
                now = now,
                policyHash = "new",
                storedHash = "old",
                ttlMs = ttl,
            ),
        )
    }

    @Test
    fun folderIsQuiet_allCachedOrOnlyFailedMisses() {
        val allCached = ThumbnailPrefetchExecutor.FolderPrefetchOutcome(
            loaded = 757, total = 757, stoppedEarly = false, failed = 0, misses = 0,
        )
        val leftoverFail = ThumbnailPrefetchExecutor.FolderPrefetchOutcome(
            loaded = 757, total = 758, stoppedEarly = false, failed = 1, misses = 1,
        )
        val firstFetch = ThumbnailPrefetchExecutor.FolderPrefetchOutcome(
            loaded = 757, total = 758, stoppedEarly = false, failed = 1, misses = 490,
        )
        val leaseFail = ThumbnailPrefetchExecutor.FolderPrefetchOutcome(
            loaded = 12, total = 100, stoppedEarly = false, failed = 0, misses = 88,
        )
        val stopped = ThumbnailPrefetchExecutor.FolderPrefetchOutcome(
            loaded = 10, total = 10, stoppedEarly = true, failed = 0, misses = 0,
        )
        assertTrue(MediaPolicyPrefetchSettled.folderIsQuiet(allCached))
        assertTrue(MediaPolicyPrefetchSettled.folderIsQuiet(leftoverFail))
        assertFalse(MediaPolicyPrefetchSettled.folderIsQuiet(firstFetch))
        assertFalse(MediaPolicyPrefetchSettled.folderIsQuiet(leaseFail))
        assertFalse(MediaPolicyPrefetchSettled.folderIsQuiet(stopped))
    }

    @Test
    fun shouldMarkSettled_requiresVerifiedQuietRun() {
        val quiet = ThumbnailPrefetchExecutor.FolderPrefetchOutcome(
            loaded = 24, total = 24, stoppedEarly = false, failed = 0, misses = 0,
        )
        assertTrue(MediaPolicyPrefetchSettled.shouldMarkSettled(false, false, listOf(quiet)))
        assertTrue(MediaPolicyPrefetchSettled.shouldMarkSettled(false, false, emptyList()))
        assertFalse(MediaPolicyPrefetchSettled.shouldMarkSettled(true, false, listOf(quiet)))
        assertFalse(MediaPolicyPrefetchSettled.shouldMarkSettled(false, true, listOf(quiet)))
    }

    @Test
    fun policyHash_stableAndSensitiveToFolderList() {
        val a = PolicyPrefetchFolder("pCloud", "Photo/(Life)", "/Photo/(Life)")
        val b = PolicyPrefetchFolder("pCloud", "Video Archive/Anime", "/Video Archive/Anime")
        assertEquals(
            MediaPolicyPrefetchSettled.policyHash(listOf(a, b)),
            MediaPolicyPrefetchSettled.policyHash(listOf(a, b)),
        )
        assertNotEquals(
            MediaPolicyPrefetchSettled.policyHash(listOf(a, b)),
            MediaPolicyPrefetchSettled.policyHash(listOf(a)),
        )
    }
}
