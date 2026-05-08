package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThumbnailFailureBlacklistTest {

    private lateinit var prefs: MemorySharedPreferences

    @Before
    fun setUp() {
        prefs = MemorySharedPreferences()
    }

    // --- BlacklistKey encoding round-trip ---

    @Test
    fun encode_decode_roundTrip_basicKey() {
        val key = BlacklistKey("MyRemote", "Videos/test.mkv", 1234567890L, 9876543210L)
        val decoded = BlacklistKey.decode(key.encode())
        assertNotNull(decoded)
        assertTrue(key == decoded)
    }

    @Test
    fun encode_decode_roundTrip_pathContainingPipe() {
        val key = BlacklistKey("remote", "path|with|pipes/file.mkv", 100L, 200L)
        val decoded = BlacklistKey.decode(key.encode())
        assertNotNull(decoded)
        assertTrue("Path containing '|' must survive encode/decode", key == decoded)
    }

    @Test
    fun encode_decode_roundTrip_zeroMetadata() {
        val key = BlacklistKey("r", "a/b.mp4", 0L, 0L)
        val decoded = BlacklistKey.decode(key.encode())
        assertNotNull(decoded)
        assertTrue(key == decoded)
    }

    @Test
    fun decode_malformed_returnsNull() {
        assertNull(BlacklistKey.decode(""))
        assertNull(BlacklistKey.decode("nopipes"))
        assertNull(BlacklistKey.decode("only|two|fields"))
        assertNull(BlacklistKey.decode("notlong|also|remote|path"))
    }

    // --- mark + isBlacklisted symmetry ---

    @Test
    fun notBlacklisted_beforeAnyMark() {
        val key = BlacklistKey("r1", "a/b/c.mkv", 100L, 200L)
        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, key))
    }

    @Test
    fun mark_thenIsBlacklisted_returnsTrue() {
        val key = BlacklistKey("r1", "a/b/c.mkv", 100L, 200L)
        ThumbnailFailureBlacklist.mark(prefs, key)
        assertTrue(ThumbnailFailureBlacklist.isBlacklisted(prefs, key))
    }

    @Test
    fun mark_idempotent_noExceptionOnRepeat() {
        val key = BlacklistKey("r1", "a.mkv", 10L, 20L)
        ThumbnailFailureBlacklist.mark(prefs, key)
        ThumbnailFailureBlacklist.mark(prefs, key)
        assertTrue(ThumbnailFailureBlacklist.isBlacklisted(prefs, key))
    }

    // --- mtime / size invalidation ---

    @Test
    fun differentMtime_notBlacklisted() {
        val written = BlacklistKey("r1", "a/b/c.mkv", 100L, 200L)
        ThumbnailFailureBlacklist.mark(prefs, written)
        val reuploaded = BlacklistKey("r1", "a/b/c.mkv", 100L, 201L)
        assertFalse(
            "Different mtime must not match the written blacklist entry",
            ThumbnailFailureBlacklist.isBlacklisted(prefs, reuploaded),
        )
    }

    @Test
    fun differentSize_notBlacklisted() {
        val written = BlacklistKey("r1", "a/b/c.mkv", 100L, 200L)
        ThumbnailFailureBlacklist.mark(prefs, written)
        val reuploaded = BlacklistKey("r1", "a/b/c.mkv", 101L, 200L)
        assertFalse(
            "Different size must not match the written blacklist entry",
            ThumbnailFailureBlacklist.isBlacklisted(prefs, reuploaded),
        )
    }

    // --- clear (per-file) ---

    @Test
    fun clear_removesOnlyTargetEntry() {
        val key1 = BlacklistKey("r1", "a.mkv", 10L, 20L)
        val key2 = BlacklistKey("r1", "b.mkv", 30L, 40L)
        ThumbnailFailureBlacklist.mark(prefs, key1)
        ThumbnailFailureBlacklist.mark(prefs, key2)
        ThumbnailFailureBlacklist.clear(prefs, key1)
        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, key1))
        assertTrue(ThumbnailFailureBlacklist.isBlacklisted(prefs, key2))
    }

    @Test
    fun clear_nonExistentKey_noException() {
        val key = BlacklistKey("r1", "a.mkv", 10L, 20L)
        ThumbnailFailureBlacklist.clear(prefs, key)
        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, key))
    }

    // --- clearAll ---

    @Test
    fun clearAll_wipesAllEntries() {
        ThumbnailFailureBlacklist.mark(prefs, BlacklistKey("r1", "a.mkv", 1L, 2L))
        ThumbnailFailureBlacklist.mark(prefs, BlacklistKey("r2", "b.mkv", 3L, 4L))
        ThumbnailFailureBlacklist.clearAll(prefs)
        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, BlacklistKey("r1", "a.mkv", 1L, 2L)))
        assertFalse(ThumbnailFailureBlacklist.isBlacklisted(prefs, BlacklistKey("r2", "b.mkv", 3L, 4L)))
    }

    @Test
    fun clearAll_emptyPrefs_noException() {
        ThumbnailFailureBlacklist.clearAll(prefs)
    }
}
