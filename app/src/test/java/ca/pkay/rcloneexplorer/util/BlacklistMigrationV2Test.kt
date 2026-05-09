package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlacklistMigrationV2Test {

    private lateinit var prefs: MemorySharedPreferences

    @Before
    fun setUp() {
        prefs = MemorySharedPreferences()
    }

    // --- Guard flag ---

    @Test
    fun migration_skippedWhenFlagAlreadySet() {
        prefs.edit().putBoolean(BlacklistMigrationV2.PREF_DONE, true).apply()
        val key = sampleKey("video.mkv")
        ThumbnailFailureBlacklist.mark(prefs, key)

        BlacklistMigrationV2.runIfNeeded(prefs)

        assertTrue(
            "Blacklist must NOT be wiped when migration flag is already set",
            ThumbnailFailureBlacklist.isBlacklisted(prefs, key),
        )
    }

    @Test
    fun migration_setsFlagAfterRun() {
        assertFalse(prefs.getBoolean(BlacklistMigrationV2.PREF_DONE, false))

        BlacklistMigrationV2.runIfNeeded(prefs)

        assertTrue(
            "PREF_DONE must be set to true after migration runs",
            prefs.getBoolean(BlacklistMigrationV2.PREF_DONE, false),
        )
    }

    @Test
    fun migration_setsFlagEvenWhenBlacklistEmpty() {
        assertNull(prefs.getStringSet(ThumbnailFailureBlacklist.PREF_KEY, null))

        BlacklistMigrationV2.runIfNeeded(prefs)

        assertTrue(
            "PREF_DONE must be set even when the blacklist was already empty",
            prefs.getBoolean(BlacklistMigrationV2.PREF_DONE, false),
        )
    }

    // --- Wipe behaviour ---

    @Test
    fun migration_wipesExistingBlacklistEntries() {
        val key1 = sampleKey("Nurse_Cosplay.mkv")
        val key2 = sampleKey("ID-037.mkv")
        ThumbnailFailureBlacklist.mark(prefs, key1)
        ThumbnailFailureBlacklist.mark(prefs, key2)

        BlacklistMigrationV2.runIfNeeded(prefs)

        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, key1))
        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, key2))
    }

    @Test
    fun migration_wipesArbitraryEntries() {
        repeat(5) { i ->
            ThumbnailFailureBlacklist.mark(prefs, sampleKey("file$i.mkv", size = (i + 1) * 1000L))
        }
        val countBefore = prefs.getStringSet(ThumbnailFailureBlacklist.PREF_KEY, null)?.size ?: 0
        assertEquals(5, countBefore)

        BlacklistMigrationV2.runIfNeeded(prefs)

        val countAfter = prefs.getStringSet(ThumbnailFailureBlacklist.PREF_KEY, null)?.size ?: 0
        assertEquals(0, countAfter)
    }

    // --- Idempotency ---

    @Test
    fun migration_idempotent_secondCallIsNoop() {
        val key = sampleKey("Tifa.mkv")
        BlacklistMigrationV2.runIfNeeded(prefs)

        ThumbnailFailureBlacklist.mark(prefs, key)
        BlacklistMigrationV2.runIfNeeded(prefs)

        assertTrue(
            "Second migration must not wipe entries added after the first run",
            ThumbnailFailureBlacklist.isBlacklisted(prefs, key),
        )
    }

    // --- Helpers ---

    private fun sampleKey(filename: String, size: Long = 7_000_000_000L) = BlacklistKey(
        remoteName = "pCloudLock",
        remoteRelativePath = "/Cosplay/$filename",
        sizeBytes = size,
        mtimeEpochMs = 1_700_000_000_000L,
    )
}
