package ca.pkay.rcloneexplorer.Glide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.preference.PreferenceManager
import com.bumptech.glide.signature.ObjectKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailCacheCompactorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cache: ThumbnailDiskCache

    @Before
    fun setUp() {
        GlideDiskCacheHolder.resetForTests()
        cache = ThumbnailDiskCache(temp.newFolder("thumbnails"), 50L * 1024L * 1024L)
    }

    @After
    fun tearDown() {
        if (::cache.isInitialized) {
            cache.close()
        }
        GlideDiskCacheHolder.resetForTests()
    }

    @Test
    fun compact_rewritesOversizedJpegAndOpaquePng_leavesOthersAndJournal() {
        val oversizedKey = ObjectKey("thumbCompact|oversized.jpg")
        val opaquePngKey = ObjectKey("thumbCompact|opaque.png")
        val alphaPngKey = ObjectKey("thumbCompact|alpha.png")
        val smallJpegKey = ObjectKey("thumbCompact|small.jpg")

        val oversizedBefore = encode(2000, 1500, Bitmap.CompressFormat.JPEG)
        val opaqueBefore = encode(400, 300, Bitmap.CompressFormat.PNG)
        val alphaBefore = encodeWithAlpha(400, 300)
        val smallBefore = encode(800, 600, Bitmap.CompressFormat.JPEG)

        cache.store(oversizedKey, oversizedBefore)
        cache.store(opaquePngKey, opaqueBefore)
        cache.store(alphaPngKey, alphaBefore)
        cache.store(smallJpegKey, smallBefore)

        val oversizedFile = cache.get(oversizedKey)!!
        val opaqueFile = cache.get(opaquePngKey)!!
        val alphaFile = cache.get(alphaPngKey)!!
        val smallFile = cache.get(smallJpegKey)!!
        val journal = File(cache.directory, "journal")
        assertTrue(journal.isFile)
        val journalBytes = journal.readBytes()

        val prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
        prefs.edit().remove(ThumbnailCacheCompactor.PREF_KEY_DONE).commit()

        val result = ThumbnailCacheCompactor.compact(cache, prefs)

        assertEquals(4, result.scanned)
        assertEquals(2, result.rewritten)
        assertEquals(0, result.errors)
        assertTrue(prefs.getBoolean(ThumbnailCacheCompactor.PREF_KEY_DONE, false))

        assertArrayEquals(journalBytes, journal.readBytes())
        assertArrayEquals(smallBefore, smallFile.readBytes())
        assertArrayEquals(alphaBefore, alphaFile.readBytes())

        val oversizedAfter = oversizedFile.readBytes()
        assertTrue(oversizedAfter.size < oversizedBefore.size)
        assertEquals(ThumbnailImageTranscoder.Format.JPEG, ThumbnailImageTranscoder.sniff(oversizedAfter))
        val oversizedBounds = bounds(oversizedAfter)
        assertTrue(maxOf(oversizedBounds.first, oversizedBounds.second) <= 1280)

        val opaqueAfter = opaqueFile.readBytes()
        assertFalse(opaqueBefore.contentEquals(opaqueAfter))
        assertEquals(ThumbnailImageTranscoder.Format.JPEG, ThumbnailImageTranscoder.sniff(opaqueAfter))
        assertNotNull(cache.get(oversizedKey))
        assertNotNull(cache.get(opaquePngKey))
    }

    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 90, 160))
            val out = ByteArrayOutputStream()
            bitmap.compress(format, 90, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeWithAlpha(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.argb(128, 40, 90, 160))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }
}
