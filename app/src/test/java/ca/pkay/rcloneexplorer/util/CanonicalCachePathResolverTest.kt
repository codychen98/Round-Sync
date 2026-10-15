package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CanonicalCachePathResolverTest {

    @Test
    fun thumbnailsDir_isUnderFilesDir() {
        val context = RuntimeEnvironment.getApplication()
        val dir = CanonicalCachePathResolver.thumbnailsDirOrNull(context)
        assertNotNull(dir)
        assertEquals(File(context.filesDir, CanonicalCachePathResolver.THUMBNAILS_DIR_NAME), dir)
        assertTrue(dir!!.absolutePath.contains("/files/"))
        assertFalse(dir.absolutePath.contains("/cache/thumbnails"))
    }

    @Test
    fun mediaCacheDir_remainsUnderCacheRoot() {
        val context = RuntimeEnvironment.getApplication()
        val dir = CanonicalCachePathResolver.mediaCacheDirOrNull(context)
        assertNotNull(dir)
        assertTrue(
            dir!!.absolutePath.contains("/cache/") ||
                dir.absolutePath.endsWith("/${CanonicalCachePathResolver.MEDIA_CACHE_DIR_NAME}"),
        )
    }

    @Test
    fun migrateLegacyThumbnails_copiesWhenDurableEmpty() {
        val context = RuntimeEnvironment.getApplication()
        val durable = CanonicalCachePathResolver.thumbnailsDirOrNull(context)!!
        durable.listFiles()?.forEach { it.delete() }

        val legacyRoot = context.externalCacheDir ?: context.cacheDir
        val legacy = File(legacyRoot, CanonicalCachePathResolver.THUMBNAILS_DIR_NAME)
        assertTrue(legacy.mkdirs() || legacy.isDirectory)
        File(legacy, "abcdef.0").writeText("jpeg-bytes")
        File(legacy, "journal").writeText("libcore.io.DiskLruCache\n")

        assertTrue(CanonicalCachePathResolver.migrateLegacyThumbnailsIfNeeded(context, durable))
        assertTrue(File(durable, "abcdef.0").isFile)
        assertTrue(File(durable, "journal").isFile)
    }
}
