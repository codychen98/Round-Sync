package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import androidx.preference.PreferenceManager
import com.bumptech.glide.signature.ObjectKey
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
class ThumbnailCachePoisonPurgeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("purge-test", Context.MODE_PRIVATE)
    private lateinit var cache: ThumbnailDiskCache

    @After
    fun tearDown() {
        if (::cache.isInitialized) {
            cache.close()
        }
        GlideDiskCacheHolder.resetForTests()
    }

    @Test
    fun purge_removesSvgEntries_keepsJpeg_andSetsFlag() {
        cache = ThumbnailDiskCache(temp.newFolder("thumbs"), 10L * 1024 * 1024)
        val svgKey = ObjectKey("thumbFile__poisoned.jpg")
        val xmlKey = ObjectKey("thumbFile__poisoned2.jpg")
        val jpegKey = ObjectKey("thumbFile__good.jpg")
        cache.store(svgKey, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())
        cache.store(xmlKey, "\uFEFF  <?xml version=\"1.0\"?><svg/>".toByteArray())
        cache.store(jpegKey, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        assertFalse(prefs.getBoolean(ThumbnailCachePoisonPurge.PREF_KEY_DONE, false))

        val result = ThumbnailCachePoisonPurge.purge(cache, prefs)

        assertEquals(ThumbnailCachePoisonPurge.Result(scanned = 3, removed = 2), result)
        assertNull(cache.get(svgKey))
        assertNull(cache.get(xmlKey))
        assertNotNull(cache.get(jpegKey))
        assertTrue(prefs.getBoolean(ThumbnailCachePoisonPurge.PREF_KEY_DONE, false))
    }

    @Test
    fun runAfterImport_reopensStaleCache_andRemovesRestoredSvg() {
        val app = RuntimeEnvironment.getApplication()
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(app)
        defaultPrefs.edit().putBoolean(ThumbnailCachePoisonPurge.PREF_KEY_DONE, true).commit()
        // Opened on "launch": empty journal, knows nothing about what the import writes later.
        val live = GlideDiskCacheHolder.get(app)!!
        assertNull(live.get(ObjectKey("thumbFile__restored.svg.jpg")))

        // Simulate the import: a second writer places journal entries + blobs on disk.
        val svgKey = ObjectKey("thumbFile__restored.svg.jpg")
        val jpegKey = ObjectKey("thumbFile__restored.jpg")
        ThumbnailDiskCache(live.directory, 10L * 1024 * 1024).also { importer ->
            importer.store(svgKey, "<?xml version=\"1.0\"?><svg/>".toByteArray())
            importer.store(jpegKey, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            importer.close()
        }

        val result = ThumbnailCachePoisonPurge.runAfterImport(app)

        assertEquals(ThumbnailCachePoisonPurge.Result(scanned = 2, removed = 1), result)
        assertNull(live.get(svgKey))
        assertNotNull(live.get(jpegKey))
    }

    @Test
    fun isPoisoned_detectsXmlAndSvgOnly() {
        assertTrue(ThumbnailCachePoisonPurge.isPoisoned("<?xml version".toByteArray()))
        assertTrue(ThumbnailCachePoisonPurge.isPoisoned("\n\t<SVG ".toByteArray()))
        assertFalse(ThumbnailCachePoisonPurge.isPoisoned(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertFalse(ThumbnailCachePoisonPurge.isPoisoned("<html>".toByteArray()))
        assertFalse(ThumbnailCachePoisonPurge.isPoisoned(ByteArray(0)))
    }
}
