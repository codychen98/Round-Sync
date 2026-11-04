package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class LocalMediaScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writesToLocalOnlyForRemoteToLocalDirections() {
        assertTrue(LocalMediaScanner.writesToLocal(SyncDirectionObject.SYNC_REMOTE_TO_LOCAL))
        assertTrue(LocalMediaScanner.writesToLocal(SyncDirectionObject.COPY_REMOTE_TO_LOCAL))
        assertFalse(LocalMediaScanner.writesToLocal(SyncDirectionObject.SYNC_LOCAL_TO_REMOTE))
        assertFalse(LocalMediaScanner.writesToLocal(SyncDirectionObject.COPY_LOCAL_TO_REMOTE))
        assertFalse(LocalMediaScanner.writesToLocal(SyncDirectionObject.SYNC_BIDIRECTIONAL))
    }

    @Test
    fun collectScanPathsIncludesRootAndNestedFilesOnly() {
        val root = tempFolder.newFolder("Pictures")
        val nested = File(root, "Album").apply { mkdirs() }
        val photo = File(nested, "a.jpg").apply { writeText("x") }
        File(nested, "skip_dir").mkdirs()

        val paths = LocalMediaScanner.collectScanPaths(root)
        assertTrue(paths.contains(root.absolutePath))
        assertTrue(paths.contains(photo.absolutePath))
        assertEquals(2, paths.size)
    }

    @Test
    fun collectScanPathsForSingleFileReturnsThatFile() {
        val file = tempFolder.newFile("one.jpg")
        assertEquals(listOf(file.absolutePath), LocalMediaScanner.collectScanPaths(file))
    }

    @Test
    fun collectScanPathsEmptyDirReturnsRootOnly() {
        val root = tempFolder.newFolder("empty")
        assertEquals(listOf(root.absolutePath), LocalMediaScanner.collectScanPaths(root))
    }
}
