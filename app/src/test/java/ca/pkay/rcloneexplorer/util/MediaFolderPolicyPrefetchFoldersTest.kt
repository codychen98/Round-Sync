package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.RemoteItem
import io.github.x0b.safdav.file.SafConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFolderPolicyPrefetchFoldersTest {

    @Test
    fun policyFolderToExplorerPath_rootAndNested() {
        assertEquals("//myremote", MediaFolderPolicyPrefetchFolders.policyFolderToExplorerPath("myremote", "/"))
        assertEquals(
            "Photos",
            MediaFolderPolicyPrefetchFolders.policyFolderToExplorerPath("myremote", "/Photos"),
        )
        assertEquals(
            "Photos/Family",
            MediaFolderPolicyPrefetchFolders.policyFolderToExplorerPath("myremote", "Photos/Family"),
        )
        assertEquals(
            "Video Archive/Anime",
            MediaFolderPolicyPrefetchFolders.policyFolderToExplorerPath("myremote", "/Video Archive/Anime"),
        )
    }

    @Test
    fun enumerate_skipsSafwAndNonThumbnailRows() {
        val prefs = MemorySharedPreferences()
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(
            editor,
            "s3remote",
            listOf(
                MediaFolderPolicyRow(path = "/Photos", thumbnail = true, cache = false),
                MediaFolderPolicyRow(path = "/Videos", thumbnail = false, cache = true),
            ),
        )
        MediaFolderPolicy.writePolicyRows(
            editor,
            "safw1",
            listOf(MediaFolderPolicyRow(path = "/Docs", thumbnail = true, cache = false)),
        )
        editor.commit()

        val remotes = listOf(
            RemoteItem("s3remote", "s3"),
            RemoteItem("safw1", SafConstants.SAF_REMOTE_NAME),
            RemoteItem("drive", "drive"),
        )
        val folders = MediaFolderPolicyPrefetchFolders.enumerate(remotes, prefs)

        assertEquals(1, folders.size)
        assertEquals("s3remote", folders[0].remoteName)
        assertEquals("Photos", folders[0].explorerDirectoryPath)
        assertEquals("/Photos", folders[0].policyRelativePath)
    }

    @Test
    fun enumerate_sortsByRemoteThenPath() {
        val prefs = MemorySharedPreferences()
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(
            editor,
            "b",
            listOf(MediaFolderPolicyRow(path = "/z", thumbnail = true, cache = false)),
        )
        MediaFolderPolicy.writePolicyRows(
            editor,
            "a",
            listOf(
                MediaFolderPolicyRow(path = "/m", thumbnail = true, cache = false),
                MediaFolderPolicyRow(path = "/a", thumbnail = true, cache = false),
            ),
        )
        editor.commit()

        val remotes = listOf(RemoteItem("b", "s3"), RemoteItem("a", "drive"))
        val folders = MediaFolderPolicyPrefetchFolders.enumerate(remotes, prefs)

        assertEquals(3, folders.size)
        assertEquals("a", folders[0].remoteName)
        assertEquals("/a", folders[0].policyRelativePath)
        assertEquals("a", folders[1].remoteName)
        assertEquals("/m", folders[1].policyRelativePath)
        assertEquals("b", folders[2].remoteName)
        assertTrue(folders.zipWithNext().all { (left, right) ->
            left.remoteName <= right.remoteName &&
                (left.remoteName != right.remoteName || left.policyRelativePath <= right.policyRelativePath)
        })
    }
}
