package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlideThumbnailCacheLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun glideDiskCacheDir_isDirectChildOfFilesDir() {
        val filesDir = temp.newFolder("app_files")
        val cacheDir = GlideThumbnailCacheLayout.glideDiskCacheDir(filesDir)
        assertEquals(filesDir, cacheDir.parentFile)
        assertEquals(GlideThumbnailCacheLayout.BASE_DIR_NAME, cacheDir.name)
    }

    @Test
    fun glideDiskCacheDir_doesNotCreateDirectory() {
        val filesDir = temp.newFolder("app_files")
        val cacheDir = GlideThumbnailCacheLayout.glideDiskCacheDir(filesDir)
        assertFalse("Directory should not be created at construction time", cacheDir.exists())
    }

    @Test
    fun glideDiskCacheDir_baseNameMatchesVersionedConstant() {
        val filesDir = temp.newFolder("app_files")
        val cacheDir = GlideThumbnailCacheLayout.glideDiskCacheDir(filesDir)
        assertEquals("thumbnail_cache_v1", cacheDir.name)
    }

    @Test
    fun defaultDiskCacheSizeBytes_is500Mib() {
        val expected = 500L * 1024L * 1024L
        assertEquals(expected, GlideThumbnailCacheLayout.DEFAULT_DISK_CACHE_SIZE_BYTES)
    }

    @Test
    fun glideDiskCacheDir_notUnderSelectedFolderMediaCacheBase() {
        val filesDir = temp.newFolder("app_files")
        val glideDir = GlideThumbnailCacheLayout.glideDiskCacheDir(filesDir)
        val mediaBase = SelectedFolderMediaCacheLayout.baseDir(filesDir)
        assertFalse(
            "Glide thumbnail cache must not be nested inside the selected-folder media cache",
            glideDir.canonicalPath.startsWith(mediaBase.canonicalPath),
        )
    }
}
