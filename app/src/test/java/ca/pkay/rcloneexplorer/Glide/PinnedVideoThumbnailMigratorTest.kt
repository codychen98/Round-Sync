package ca.pkay.rcloneexplorer.Glide

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class PinnedVideoThumbnailMigratorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun migrateFileIfOversized_shrinksLargeJpeg() {
        val root = temp.newFolder("pins")
        val stable = "/remote/Anime/big.mkv"
        val large = encode(1280, 720)
        assertTrue(PinnedVideoThumbnailStore.put(root, stable, large))
        assertTrue(
            PinnedVideoThumbnailMigrator.longestEdgePx(large) > PinnedVideoThumbnailStore.MAX_EDGE_PX,
        )

        val result = PinnedVideoThumbnailMigrator.migrateFileIfOversized(root, stable)
        assertNotNull(result)
        assertEquals(large.size, result!!.oldBytes)
        assertTrue(result.newBytes > 0)
        assertTrue(result.newBytes < result.oldBytes)

        val migrated = PinnedVideoThumbnailStore.fileIfPresent(root, stable)!!.readBytes()
        val edge = PinnedVideoThumbnailMigrator.longestEdgePx(migrated)
        assertTrue(edge in 1..PinnedVideoThumbnailStore.MAX_EDGE_PX)
    }

    @Test
    fun migrateFileIfOversized_skipsAlreadyBounded() {
        val root = temp.newFolder("pins-small")
        val stable = "/remote/Anime/small.mkv"
        val small = encode(400, 300)
        assertTrue(PinnedVideoThumbnailStore.put(root, stable, small))

        assertNull(PinnedVideoThumbnailMigrator.migrateFileIfOversized(root, stable))
        val after = PinnedVideoThumbnailStore.fileIfPresent(root, stable)!!.readBytes()
        assertEquals(small.size, after.size)
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
