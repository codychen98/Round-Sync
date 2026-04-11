package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelectedFolderMediaCacheLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun baseDir_isUnderFilesDir() {
        val files = temp.newFolder("app_files")
        val base = SelectedFolderMediaCacheLayout.baseDir(files)
        assertEquals(files, base.parentFile)
        assertEquals(SelectedFolderMediaCacheLayout.BASE_DIR_NAME, base.name)
    }

    @Test
    fun subdirs_areUnderBase() {
        val files = temp.newFolder("app_files")
        val exo = SelectedFolderMediaCacheLayout.exoSimpleCacheDir(files)
        val http = SelectedFolderMediaCacheLayout.okHttpHttpCacheDir(files)
        assertEquals(SelectedFolderMediaCacheLayout.baseDir(files), exo.parentFile)
        assertEquals(SelectedFolderMediaCacheLayout.SUBDIR_EXO_SIMPLE_CACHE, exo.name)
        assertEquals(SelectedFolderMediaCacheLayout.baseDir(files), http.parentFile)
        assertEquals(SelectedFolderMediaCacheLayout.SUBDIR_OKHTTP_HTTP_CACHE, http.name)
    }

    @Test
    fun clampMaxCacheBytes_defaultUnchanged() {
        assertEquals(
            SelectedFolderMediaCacheLayout.DEFAULT_MAX_CACHE_BYTES,
            SelectedFolderMediaCacheLayout.clampMaxCacheBytes(
                SelectedFolderMediaCacheLayout.DEFAULT_MAX_CACHE_BYTES,
            ),
        )
    }

    @Test
    fun clampMaxCacheBytes_belowMin_becomesMin() {
        assertEquals(
            SelectedFolderMediaCacheLayout.MIN_MAX_CACHE_BYTES,
            SelectedFolderMediaCacheLayout.clampMaxCacheBytes(0L),
        )
    }

    @Test
    fun clampMaxCacheBytes_aboveMax_becomesMax() {
        assertEquals(
            SelectedFolderMediaCacheLayout.MAX_MAX_CACHE_BYTES,
            SelectedFolderMediaCacheLayout.clampMaxCacheBytes(Long.MAX_VALUE),
        )
    }

    @Test
    fun quotaShares_sum_notExceedTotal() {
        val total = SelectedFolderMediaCacheLayout.DEFAULT_MAX_CACHE_BYTES
        val exo = SelectedFolderMediaCacheLayout.exoMaxBytesForTotal(total)
        val ok = SelectedFolderMediaCacheLayout.okHttpMaxBytesForTotal(total)
        assertTrue(exo >= 0L)
        assertTrue(ok >= 0L)
        assertTrue(exo + ok <= total)
    }

    @Test
    fun readClampedTotalMaxBytesFromPrefs_missingKey_usesDefault() {
        val prefs = MemorySharedPreferences()
        assertEquals(
            SelectedFolderMediaCacheLayout.DEFAULT_MAX_CACHE_BYTES,
            SelectedFolderMediaCacheLayout.readClampedTotalMaxBytesFromPrefs(prefs),
        )
    }

    @Test
    fun readClampedTotalMaxBytesFromPrefs_clampsStoredValue() {
        val prefs = MemorySharedPreferences()
        prefs.edit().putLong(SelectedFolderMediaCacheLayout.PREF_KEY_MAX_CACHE_BYTES_V1, 1024L).commit()
        assertEquals(
            SelectedFolderMediaCacheLayout.MIN_MAX_CACHE_BYTES,
            SelectedFolderMediaCacheLayout.readClampedTotalMaxBytesFromPrefs(prefs),
        )
    }
}
