package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyPrefetchMigrationV1Test {

    private lateinit var prefs: MemorySharedPreferences

    private val enqueuedThumbnails = mutableListOf<Pair<String, String>>()
    private val enqueuedCaches = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        prefs = MemorySharedPreferences()
        enqueuedThumbnails.clear()
        enqueuedCaches.clear()
    }

    private fun runMigration(remotes: List<RemoteItem>) {
        PolicyPrefetchMigrationV1.runIfNeeded(
            prefs = prefs,
            remoteSupplier = { remotes },
            enqueueThumbnail = { remote, folder -> enqueuedThumbnails.add(remote to folder) },
            enqueueCache = { remote, folder -> enqueuedCaches.add(remote to folder) },
        )
    }

    // --- Guard flag ---

    @Test
    fun migration_skippedWhenFlagAlreadySet() {
        prefs.edit().putBoolean(PolicyPrefetchMigrationV1.PREF_DONE, true).apply()
        val remote = RemoteItem("myRemote", "sftp")
        writeRow(prefs, "myRemote", "/Videos", thumbnail = true, cache = false)

        runMigration(listOf(remote))

        assertTrue("No enqueue should happen when migration is already done", enqueuedThumbnails.isEmpty())
        assertTrue(enqueuedCaches.isEmpty())
    }

    @Test
    fun migration_setsFlagAfterRun() {
        runMigration(emptyList())
        assertTrue(prefs.getBoolean(PolicyPrefetchMigrationV1.PREF_DONE, false))
    }

    @Test
    fun migration_idempotent_secondCallIsNoop() {
        val remote = RemoteItem("myRemote", "sftp")
        writeRow(prefs, "myRemote", "/Videos", thumbnail = true, cache = false)

        runMigration(listOf(remote))
        val firstCount = enqueuedThumbnails.size

        enqueuedThumbnails.clear()
        runMigration(listOf(remote))

        assertEquals("Second call must enqueue nothing (flag guards it)", 0, enqueuedThumbnails.size)
        assertEquals(1, firstCount)
    }

    // --- Thumbnail rows ---

    @Test
    fun migration_enqueuesThumbnailForCheckedRow() {
        val remote = RemoteItem("photos", "s3")
        writeRow(prefs, "photos", "/Gallery", thumbnail = true, cache = false)

        runMigration(listOf(remote))

        assertEquals(1, enqueuedThumbnails.size)
        assertEquals("photos" to "/Gallery", enqueuedThumbnails[0])
        assertTrue(enqueuedCaches.isEmpty())
    }

    @Test
    fun migration_enqueuesCacheForCheckedRow() {
        val remote = RemoteItem("videos", "drive")
        writeRow(prefs, "videos", "/Cosplay", thumbnail = false, cache = true)

        runMigration(listOf(remote))

        assertTrue(enqueuedThumbnails.isEmpty())
        assertEquals(1, enqueuedCaches.size)
        assertEquals("videos" to "/Cosplay", enqueuedCaches[0])
    }

    @Test
    fun migration_enqueuesBothForRowWithBothEnabled() {
        val remote = RemoteItem("media", "sftp")
        writeRow(prefs, "media", "/Movies", thumbnail = true, cache = true)

        runMigration(listOf(remote))

        assertEquals(1, enqueuedThumbnails.size)
        assertEquals(1, enqueuedCaches.size)
        assertEquals("media" to "/Movies", enqueuedThumbnails[0])
        assertEquals("media" to "/Movies", enqueuedCaches[0])
    }

    @Test
    fun migration_skipsRowWithBothDisabled() {
        val remote = RemoteItem("media", "sftp")
        writeRow(prefs, "media", "/Unused", thumbnail = false, cache = false)

        runMigration(listOf(remote))

        assertTrue(enqueuedThumbnails.isEmpty())
        assertTrue(enqueuedCaches.isEmpty())
    }

    // --- Multiple remotes and rows ---

    @Test
    fun migration_handlesMultipleRemotesAndRows() {
        val remoteA = RemoteItem("alpha", "sftp")
        val remoteB = RemoteItem("beta", "s3")
        writeRow(prefs, "alpha", "/A1", thumbnail = true, cache = false)
        writeRow(prefs, "alpha", "/A2", thumbnail = false, cache = true)
        writeRow(prefs, "beta", "/B1", thumbnail = true, cache = true)

        runMigration(listOf(remoteA, remoteB))

        assertEquals(2, enqueuedThumbnails.size) // alpha/A1 + beta/B1
        assertEquals(2, enqueuedCaches.size)     // alpha/A2 + beta/B1
        assertTrue(enqueuedThumbnails.any { it == "alpha" to "/A1" })
        assertTrue(enqueuedThumbnails.any { it == "beta" to "/B1" })
        assertTrue(enqueuedCaches.any { it == "alpha" to "/A2" })
        assertTrue(enqueuedCaches.any { it == "beta" to "/B1" })
    }

    // --- SAF remote skip ---

    @Test
    fun migration_skipsSafRemote() {
        val safRemote = RemoteItem("local-saf-remote", "local-saf")
        writeRow(prefs, "local-saf-remote", "/sdcard", thumbnail = true, cache = true)

        runMigration(listOf(safRemote))

        assertTrue("SAF remotes must be skipped", enqueuedThumbnails.isEmpty())
        assertTrue(enqueuedCaches.isEmpty())
    }

    @Test
    fun migration_processesNonSafAlongsideSaf() {
        val safRemote = RemoteItem("saf", "local-saf")
        val normalRemote = RemoteItem("cloud", "dropbox")
        writeRow(prefs, "saf", "/sdcard", thumbnail = true, cache = true)
        writeRow(prefs, "cloud", "/Photos", thumbnail = true, cache = false)

        runMigration(listOf(safRemote, normalRemote))

        assertEquals(1, enqueuedThumbnails.size)
        assertEquals("cloud" to "/Photos", enqueuedThumbnails[0])
        assertTrue(enqueuedCaches.isEmpty())
    }

    // --- Empty / no-op cases ---

    @Test
    fun migration_noRemotes_completes() {
        runMigration(emptyList())
        assertTrue(enqueuedThumbnails.isEmpty())
        assertTrue(enqueuedCaches.isEmpty())
        assertTrue(prefs.getBoolean(PolicyPrefetchMigrationV1.PREF_DONE, false))
    }

    @Test
    fun migration_noRows_completes() {
        val remote = RemoteItem("empty", "s3")
        runMigration(listOf(remote))
        assertTrue(enqueuedThumbnails.isEmpty())
        assertTrue(enqueuedCaches.isEmpty())
    }

    // --- Exception tolerance ---

    @Test
    fun migration_continuesAfterEnqueueException() {
        val remote = RemoteItem("media", "sftp")
        writeRow(prefs, "media", "/Folder1", thumbnail = true, cache = false)
        writeRow(prefs, "media", "/Folder2", thumbnail = true, cache = false)

        var calls = 0
        PolicyPrefetchMigrationV1.runIfNeeded(
            prefs = prefs,
            remoteSupplier = { listOf(remote) },
            enqueueThumbnail = { remote2, folder ->
                calls++
                if (calls == 1) throw RuntimeException("simulated failure")
                enqueuedThumbnails.add(remote2 to folder)
            },
            enqueueCache = { r, f -> enqueuedCaches.add(r to f) },
        )

        assertEquals(
            "Second folder should still be enqueued despite first throwing",
            1,
            enqueuedThumbnails.size,
        )
        assertTrue(prefs.getBoolean(PolicyPrefetchMigrationV1.PREF_DONE, false))
    }

    @Test
    fun migration_flagSetEvenIfRemoteSupplierThrows() {
        assertFalse(prefs.getBoolean(PolicyPrefetchMigrationV1.PREF_DONE, false))

        PolicyPrefetchMigrationV1.runIfNeeded(
            prefs = prefs,
            remoteSupplier = { throw RuntimeException("rclone config unreadable") },
            enqueueThumbnail = { r, f -> enqueuedThumbnails.add(r to f) },
            enqueueCache = { r, f -> enqueuedCaches.add(r to f) },
        )

        assertTrue(
            "Flag must be set even when remoteSupplier throws so we don't retry forever",
            prefs.getBoolean(PolicyPrefetchMigrationV1.PREF_DONE, false),
        )
        assertTrue(enqueuedThumbnails.isEmpty())
    }

    // --- Helpers ---

    private fun writeRow(
        prefs: MemorySharedPreferences,
        remoteName: String,
        path: String,
        thumbnail: Boolean,
        cache: Boolean,
    ) {
        val existing = MediaFolderPolicy.readPolicyRows(prefs, remoteName).toMutableList()
        existing.removeAll { it.path == path }
        if (thumbnail || cache) {
            existing.add(MediaFolderPolicyRow(path = path, thumbnail = thumbnail, cache = cache))
        }
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(editor, remoteName, existing)
        editor.apply()
    }
}
