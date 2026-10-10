package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinnedVideoThumbnailStoreTest {

    @Test
    fun putThenRead_roundTripsBytes() {
        val root = tempRoot()
        val stable = "/remote/Anime/episode.mkv"
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        assertTrue(PinnedVideoThumbnailStore.put(root, stable, jpeg))
        val file = PinnedVideoThumbnailStore.fileIfPresent(root, stable)
        assertNotNull(file)
        assertArrayEquals(jpeg, file!!.readBytes())
    }

    @Test
    fun secondPut_replacesPreviousBytes() {
        val root = tempRoot()
        val stable = "/remote/Anime/episode.mkv"
        assertTrue(PinnedVideoThumbnailStore.put(root, stable, byteArrayOf(0x01)))
        val replacement = byteArrayOf(0x02, 0x03)
        assertTrue(PinnedVideoThumbnailStore.put(root, stable, replacement))
        val file = PinnedVideoThumbnailStore.fileIfPresent(root, stable)
        assertNotNull(file)
        assertArrayEquals(replacement, file!!.readBytes())
    }

    @Test
    fun emptyInput_writesNothing() {
        val root = tempRoot()
        assertFalse(PinnedVideoThumbnailStore.put(root, "", byteArrayOf(0x01)))
        assertFalse(PinnedVideoThumbnailStore.put(root, "/remote/a.mkv", byteArrayOf()))
        assertNull(PinnedVideoThumbnailStore.fileIfPresent(root, "/remote/a.mkv"))
    }

    private fun tempRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "pinned-thumbs-" + System.nanoTime())
        root.mkdirs()
        root.deleteOnExit()
        return root
    }
}
