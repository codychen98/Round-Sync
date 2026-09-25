package ca.pkay.rcloneexplorer.Glide

import com.bumptech.glide.disklrucache.DiskLruCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ThumbnailDiskCacheReconcilerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun isValidDiskLruKey_acceptsSafeKeyHex() {
        assertTrue(ThumbnailDiskCacheReconciler.isValidDiskLruKey("a".repeat(64)))
        assertTrue(ThumbnailDiskCacheReconciler.isValidDiskLruKey("abc_def-012"))
        assertFalse(ThumbnailDiskCacheReconciler.isValidDiskLruKey(""))
        assertFalse(ThumbnailDiskCacheReconciler.isValidDiskLruKey("has/slash"))
        assertFalse(ThumbnailDiskCacheReconciler.isValidDiskLruKey("UPPER"))
    }

    @Test
    fun rebuildJournalFromValueFiles_registersOrphanBlobs() {
        val cacheDir = temp.newFolder("thumbnails")
        val key = "a".repeat(64)
        File(cacheDir, "$key.0").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        // Stale/corrupt journal that does not list the blob.
        File(cacheDir, "journal").writeText("not-a-valid-journal\n")

        val result = ThumbnailDiskCacheReconciler.rebuildJournalFromValueFiles(cacheDir)
        assertEquals(1, result.registered)
        assertEquals(0, result.skippedInvalid)

        val cache = DiskLruCache.open(cacheDir, 1, 1, 500L * 1024L * 1024L)
        try {
            assertNotNull(cache.get(key))
        } finally {
            cache.close()
        }
    }

    @Test
    fun rebuildJournalFromValueFiles_skipsEmptyBlobs() {
        val cacheDir = temp.newFolder("thumbnails")
        val key = "b".repeat(64)
        File(cacheDir, "$key.0").writeBytes(ByteArray(0))

        val result = ThumbnailDiskCacheReconciler.rebuildJournalFromValueFiles(cacheDir)
        assertEquals(0, result.registered)
    }
}
