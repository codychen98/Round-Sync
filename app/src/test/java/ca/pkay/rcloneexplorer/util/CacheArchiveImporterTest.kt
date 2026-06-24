package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

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
    fun isSafeRelativePath_rejectsTraversalAndEmptySegments() {
        assertFalse(CacheArchiveImporter.isSafeRelativePath(""))
        assertFalse(CacheArchiveImporter.isSafeRelativePath("a/../b"))
        assertFalse(CacheArchiveImporter.isSafeRelativePath("a//b"))
        assertTrue(CacheArchiveImporter.isSafeRelativePath("a/b/c.dat"))
    }
}
