package ca.pkay.rcloneexplorer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderCollageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun putBytes_writesJpegFile() {
        val root = temp.newFolder("collages")
        val jpeg = encode(64, 64)
        assertTrue(FolderCollageStore.putBytes(root, "remote", "Video Archive/Anime", jpeg))
        val files = root.listFiles()!!.filter { it.name.endsWith(".jpg") }
        assertEquals(1, files.size)
        assertEquals(jpeg.size.toLong(), files[0].length())
    }

    @Test
    fun putBytes_emptyInput_writesNothing() {
        val root = temp.newFolder("empty")
        assertFalse(FolderCollageStore.putBytes(root, "", "path", byteArrayOf(1)))
        assertFalse(FolderCollageStore.putBytes(root, "r", "path", byteArrayOf()))
        assertTrue(root.listFiles().isNullOrEmpty() || root.listFiles()!!.none { it.name.endsWith(".jpg") })
    }

    @Test
    fun putBytes_sameKey_replaces() {
        val root = temp.newFolder("replace")
        val first = encode(32, 32)
        val second = encode(48, 48)
        assertTrue(FolderCollageStore.putBytes(root, "r", "f", first))
        assertTrue(FolderCollageStore.putBytes(root, "r", "f", second))
        val jpg = root.listFiles()!!.first { it.name.endsWith(".jpg") }
        assertEquals(second.size.toLong(), jpg.length())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpg.absolutePath, bounds)
        assertEquals(48, bounds.outWidth)
    }

    private fun encode(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 90, 160))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
