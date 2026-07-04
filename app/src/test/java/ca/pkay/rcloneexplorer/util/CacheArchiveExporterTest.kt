package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CacheArchiveExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun relativePathWithinRoot_usesForwardSlashes() {
        val root = temp.newFolder("thumbnails")
        val nested = File(root, "a/b/c.dat").also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }
        assertEquals("a/b/c.dat", CacheArchiveExporter.relativePathWithinRoot(root, nested))
    }

    @Test
    fun zipEntryName_joinsPrefixAndRelativePath() {
        assertEquals(
            "cache/thumbnails/hash/0",
            CacheArchiveExporter.zipEntryName(
                CacheArchiveExporter.ZIP_THUMBNAILS_PREFIX,
                "hash/0",
            ),
        )
        assertEquals(
            "cache/media_cache/exo_simple_cache/segment.db",
            CacheArchiveExporter.zipEntryName(
                CacheArchiveExporter.ZIP_MEDIA_CACHE_PREFIX,
                "exo_simple_cache/segment.db",
            ),
        )
    }

    @Test
    fun isNonEmptyDirectory_falseForMissingOrEmpty() {
        val empty = temp.newFolder("empty")
        assertFalse(CacheArchiveExporter.isNonEmptyDirectory(empty))
        assertFalse(CacheArchiveExporter.isNonEmptyDirectory(File(empty, "missing")))
    }

    @Test
    fun isNonEmptyDirectory_trueWhenFilePresent() {
        val root = temp.newFolder("cache")
        File(root, "entry").writeText("data")
        assertTrue(CacheArchiveExporter.isNonEmptyDirectory(root))
    }

    @Test
    fun legacyMediaCacheMapsUnderCacheMediaCachePrefix() {
        val legacyBase = temp.newFolder("selected_folder_media_cache")
        val exoFile = File(legacyBase, "exo_simple_cache/seg.v3.exo").also {
            it.parentFile.mkdirs()
            it.writeText("exo")
        }
        val relative = CacheArchiveExporter.relativePathWithinRoot(legacyBase, exoFile)
        val entry = CacheArchiveExporter.zipEntryName(
            CacheArchiveExporter.ZIP_MEDIA_CACHE_PREFIX,
            relative,
        )
        assertEquals("cache/media_cache/exo_simple_cache/seg.v3.exo", entry)
    }

    @Test
    fun hasCacheEntriesToExport_falseWhenDirsMissingOrEmpty() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(CacheArchiveExporter.hasCacheEntriesToExport(context))
    }

    @Test
    fun hasCacheEntriesToExport_trueWhenThumbnailsPresent() {
        val context = RuntimeEnvironment.getApplication()
        val thumbnailsDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context)
        requireNotNull(thumbnailsDir)
        File(thumbnailsDir, "probe.dat").writeText("x")
        assertTrue(CacheArchiveExporter.hasCacheEntriesToExport(context))
    }

    @Test
    fun hasCacheEntriesToExport_falseWhenOnlyMediaCachePresent() {
        val context = RuntimeEnvironment.getApplication()
        val mediaCacheDir = CanonicalCachePathResolver.mediaCacheDirOrNull(context)
        requireNotNull(mediaCacheDir)
        File(mediaCacheDir, "exo_simple_cache/seg.v3.exo").also {
            it.parentFile.mkdirs()
            it.writeText("exo")
        }
        assertFalse(CacheArchiveExporter.hasCacheEntriesToExport(context))
    }
}
