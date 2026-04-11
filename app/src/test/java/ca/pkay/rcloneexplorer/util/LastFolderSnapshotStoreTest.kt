package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastFolderSnapshotStoreTest {

    @Test
    fun persist_then_read_roundTrip() {
        val prefs = MemorySharedPreferences()
        LastFolderSnapshotStore.persist(prefs, "myremote", "//myremote/Photos")
        val snap = LastFolderSnapshotStore.read(prefs)
        assertEquals("myremote", snap?.remoteName)
        assertEquals("//myremote/Photos", snap?.directoryPath)
    }

    @Test
    fun read_missingKeys_returnsNull() {
        val prefs = MemorySharedPreferences()
        assertNull(LastFolderSnapshotStore.read(prefs))
    }

    @Test
    fun persist_blankRemote_doesNotWrite() {
        val prefs = MemorySharedPreferences()
        LastFolderSnapshotStore.persist(prefs, "   ", "//x/y")
        assertNull(LastFolderSnapshotStore.read(prefs))
    }

    @Test
    fun persist_blankPath_doesNotWrite() {
        val prefs = MemorySharedPreferences()
        LastFolderSnapshotStore.persist(prefs, "r", "")
        assertNull(LastFolderSnapshotStore.read(prefs))
    }

    @Test
    fun persist_blankRemote_doesNotOverwriteExisting() {
        val prefs = MemorySharedPreferences()
        LastFolderSnapshotStore.persist(prefs, "r", "//r/a")
        LastFolderSnapshotStore.persist(prefs, "", "//r/b")
        val snap = LastFolderSnapshotStore.read(prefs)
        assertEquals("r", snap?.remoteName)
        assertEquals("//r/a", snap?.directoryPath)
    }
}
