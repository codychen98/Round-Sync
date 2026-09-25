package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.bumptech.glide.disklrucache.DiskLruCache
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class CacheArchiveImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun targetFileForEntry_mapsThumbnailsUnderCanonicalDir() {
        val context = RuntimeEnvironment.getApplication()
        val thumbnailsDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context)
        requireNotNull(thumbnailsDir)

        val target = CacheArchiveImporter.targetFileForEntry(
            context,
            "cache/thumbnails/hash/0",
        )

        assertEquals(File(thumbnailsDir, "hash/0"), target)
    }

    @Test
    fun targetFileForEntry_mapsMediaCacheUnderCanonicalDir() {
        val context = RuntimeEnvironment.getApplication()
        val mediaCacheDir = CanonicalCachePathResolver.mediaCacheDirOrNull(context)
        requireNotNull(mediaCacheDir)

        val target = CacheArchiveImporter.targetFileForEntry(
            context,
            "cache/media_cache/exo_simple_cache/segment.db",
        )

        assertEquals(File(mediaCacheDir, "exo_simple_cache/segment.db"), target)
    }

    @Test
    fun targetFileForEntry_ignoresNonCacheEntries() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(CacheArchiveImporter.targetFileForEntry(context, "rclone.conf"))
        assertNull(CacheArchiveImporter.targetFileForEntry(context, "cache/other/file.dat"))
    }

    @Test
    fun targetFileForEntry_rejectsPathTraversal() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(
            CacheArchiveImporter.targetFileForEntry(
                context,
                "cache/thumbnails/../escape.dat",
            ),
        )
    }

    @Test
    fun shouldSkipExtraction_whenSameSizeAndNewer() {
        val file = temp.newFile("cached.dat")
        file.writeBytes(ByteArray(4) { 1 })
        file.setLastModified(2_000L)

        assertTrue(CacheArchiveImporter.shouldSkipExtraction(file, 4L, 1_000L))
    }

    @Test
    fun shouldSkipExtraction_falseWhenSizeDiffers() {
        val file = temp.newFile("cached.dat")
        file.writeBytes(ByteArray(4) { 1 })
        file.setLastModified(2_000L)

        assertFalse(CacheArchiveImporter.shouldSkipExtraction(file, 8L, 1_000L))
    }

    @Test
    fun shouldSkipExtraction_falseWhenLocalOlder() {
        val file = temp.newFile("cached.dat")
        file.writeBytes(ByteArray(4) { 1 })
        file.setLastModified(500L)

        assertFalse(CacheArchiveImporter.shouldSkipExtraction(file, 4L, 1_000L))
    }

    @Test
    fun shouldSkipExtraction_whenSizeUnknownAndLocalExists() {
        val file = temp.newFile("cached.dat")
        file.writeBytes(ByteArray(4) { 1 })
        file.setLastModified(2_000L)

        // ZipInputStream often reports -1; preserve non-empty local blobs.
        assertTrue(CacheArchiveImporter.shouldSkipExtraction(file, -1L, -1L))
        assertTrue(CacheArchiveImporter.shouldSkipExtraction(file, -1L, 1_000L))
    }

    @Test
    fun shouldSkipExtraction_falseWhenSizeUnknownButLocalOlder() {
        val file = temp.newFile("cached.dat")
        file.writeBytes(ByteArray(4) { 1 })
        file.setLastModified(500L)

        assertFalse(CacheArchiveImporter.shouldSkipExtraction(file, -1L, 1_000L))
    }

    @Test
    fun shouldSkipExtraction_falseWhenEmptyLocal() {
        val file = temp.newFile("empty.dat")
        file.writeBytes(ByteArray(0))
        file.setLastModified(2_000L)

        assertFalse(CacheArchiveImporter.shouldSkipExtraction(file, -1L, -1L))
        assertFalse(CacheArchiveImporter.shouldSkipExtraction(file, 0L, -1L))
    }

    @Test
    fun isSafeRelativePath_rejectsTraversalAndEmptySegments() {
        assertFalse(CacheArchiveImporter.isSafeRelativePath(""))
        assertFalse(CacheArchiveImporter.isSafeRelativePath("a/../b"))
        assertFalse(CacheArchiveImporter.isSafeRelativePath("a//b"))
        assertTrue(CacheArchiveImporter.isSafeRelativePath("a/b/c.dat"))
    }

    @Test
    fun targetFileForEntry_ignoresGlideJournalSidecars() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(CacheArchiveImporter.targetFileForEntry(context, "cache/thumbnails/journal"))
        assertNull(CacheArchiveImporter.targetFileForEntry(context, "cache/thumbnails/journal.tmp"))
        assertNull(CacheArchiveImporter.targetFileForEntry(context, "cache/thumbnails/journal.bkp"))
    }

    @Test
    fun extractFromZipFile_skipsExistingEqualBlob() {
        val context = RuntimeEnvironment.getApplication()
        val thumbnailsDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context)
        requireNotNull(thumbnailsDir)

        val relative = "abc123.0"
        val local = File(thumbnailsDir, relative)
        local.writeBytes(byteArrayOf(1, 2, 3, 4))
        local.setLastModified(5_000L)

        val zip = temp.newFile("backup.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            val entry = ZipEntry("cache/thumbnails/$relative")
            entry.size = 4L
            entry.time = 1_000L
            zos.putNextEntry(entry)
            zos.write(byteArrayOf(9, 9, 9, 9))
            zos.closeEntry()
        }

        val result = CacheArchiveImporter.extractFromZipFile(context, zip)
        assertEquals(0, result.extracted)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertTrue(result.reconciled >= 1)
        assertTrue(local.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun extractFromZipFile_reconcilesRestoredBlobsIntoJournal() {
        val context = RuntimeEnvironment.getApplication()
        val thumbnailsDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context)
        requireNotNull(thumbnailsDir)

        val key = "c".repeat(64)
        val zip = temp.newFile("backup-reconcile.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            val blob = ZipEntry("cache/thumbnails/$key.0")
            blob.size = 3L
            blob.time = 1_000L
            zos.putNextEntry(blob)
            zos.write(byteArrayOf(7, 8, 9))
            zos.closeEntry()
            // Stale journal must be ignored; reconciler rebuilds from the blob.
            zos.putNextEntry(ZipEntry("cache/thumbnails/journal"))
            zos.write("corrupt-journal".toByteArray())
            zos.closeEntry()
        }

        val result = CacheArchiveImporter.extractFromZipFile(context, zip)
        assertEquals(1, result.extracted)
        assertTrue(result.reconciled >= 1)
        DiskLruCache.open(
            thumbnailsDir,
            1,
            1,
            500L * 1024L * 1024L,
        ).use { cache ->
            assertNotNull(cache.get(key))
        }
    }
}
