package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThumbnailDiskCacheSizeTest {

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun compute_smallFreeSpaceAndEmptyCache_clampsToFloor() {
        // 400 MiB free -> 100 MiB target, below the 500 MiB floor.
        assertEquals(ThumbnailDiskCacheSize.FLOOR_BYTES, ThumbnailDiskCacheSize.compute(400L * mib, 0L))
    }

    @Test
    fun compute_hugeFreeSpace_clampsToCeiling() {
        // 64 GiB free -> 16 GiB target, above the 2 GiB ceiling.
        assertEquals(ThumbnailDiskCacheSize.CEILING_BYTES, ThumbnailDiskCacheSize.compute(64L * gib, 0L))
    }

    @Test
    fun compute_midRange_isQuarterOfFreePlusUsage() {
        // 2 GiB free -> 512 MiB, plus 517 MiB already on disk (the sample backup) -> 1029 MiB.
        val expected = 512L * mib + 517L * mib
        assertEquals(expected, ThumbnailDiskCacheSize.compute(2L * gib, 517L * mib))
    }

    @Test
    fun compute_existingUsageAboveFloor_neverDropsBelowUsage() {
        // Almost no free space but 900 MiB restored: cap must cover what is on disk.
        val usage = 900L * mib
        val result = ThumbnailDiskCacheSize.compute(4L * mib, usage)
        assertEquals(usage + 1L * mib, result)
    }

    @Test
    fun compute_negativeInputs_treatedAsZero() {
        assertEquals(ThumbnailDiskCacheSize.FLOOR_BYTES, ThumbnailDiskCacheSize.compute(-1L, -1L))
    }

    @Test
    fun forDir_disablesSizeEviction() {
        ThumbnailDiskCacheSize.clearMemoForTests()
        val dir = File(System.getProperty("java.io.tmpdir"), "thumb-size-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs() || dir.isDirectory)
        try {
            assertEquals(
                ThumbnailDiskCacheSize.NO_SIZE_EVICTION_BYTES,
                ThumbnailDiskCacheSize.forDir(dir),
            )
            assertEquals(
                ThumbnailDiskCacheSize.NO_SIZE_EVICTION_BYTES,
                ThumbnailDiskCacheSize.forDir(dir),
            )
        } finally {
            dir.delete()
            ThumbnailDiskCacheSize.clearMemoForTests()
        }
    }
}

