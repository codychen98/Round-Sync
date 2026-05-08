package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePathCacheKeyFactoryTest {

    // --- basic extraction ---

    @Test
    fun extractStableKey_typicalUrl_returnsRemoteAndPath() {
        val path = "/abc123token/MyRemote/Videos/Nurse_Cosplay.mkv"
        val key = extractStableKey(path)
        assertEquals("MyRemote/Videos/Nurse_Cosplay.mkv", key)
    }

    @Test
    fun extractStableKey_topLevelFile_returnsRemoteAndName() {
        val path = "/tokenXYZ/SomeRemote/file.mp4"
        val key = extractStableKey(path)
        assertEquals("SomeRemote/file.mp4", key)
    }

    @Test
    fun extractStableKey_deepPath_preservesAllSegments() {
        val path = "/t0k3n/Remote/a/b/c/deep.mkv"
        val key = extractStableKey(path)
        assertEquals("Remote/a/b/c/deep.mkv", key)
    }

    @Test
    fun extractStableKey_noLeadingSlash_stillWorks() {
        val path = "token/Remote/file.mp4"
        val key = extractStableKey(path)
        assertEquals("Remote/file.mp4", key)
    }

    // --- session-stability: different ports and auth tokens, same file ---

    @Test
    fun extractStableKey_differentPorts_sameResult() {
        val session1 = "/authSession1/MyRemote/Videos/file.mkv"
        val session2 = "/authSession2/MyRemote/Videos/file.mkv"
        assertEquals(extractStableKey(session1), extractStableKey(session2))
    }

    @Test
    fun extractStableKey_samePort_differentAuthToken_sameResult() {
        val url1 = "/token_a9b1/Cosplay/folder/video.mkv"
        val url2 = "/token_ff02/Cosplay/folder/video.mkv"
        assertEquals(extractStableKey(url1), extractStableKey(url2))
    }

    // --- different files produce different keys ---

    @Test
    fun extractStableKey_differentFiles_differentKeys() {
        val path1 = "/token/Remote/a.mkv"
        val path2 = "/token/Remote/b.mkv"
        assertNotEquals(extractStableKey(path1), extractStableKey(path2))
    }

    @Test
    fun extractStableKey_differentRemotes_differentKeys() {
        val path1 = "/token/RemoteA/file.mkv"
        val path2 = "/token/RemoteB/file.mkv"
        assertNotEquals(extractStableKey(path1), extractStableKey(path2))
    }

    // --- auth-token drop: token does NOT appear in the key ---

    @Test
    fun extractStableKey_authTokenNotInResult() {
        val token = "secretAuthToken99"
        val path = "/$token/Remote/folder/file.mkv"
        val key = extractStableKey(path)
        assertEquals("Remote/folder/file.mkv", key)
        assert(key != null && !key.contains(token)) {
            "Auth token must not appear in the stable key"
        }
    }

    // --- edge / fallback cases ---

    @Test
    fun extractStableKey_nullPath_returnsNull() {
        assertNull(extractStableKey(null))
    }

    @Test
    fun extractStableKey_emptyPath_returnsNull() {
        assertNull(extractStableKey(""))
    }

    @Test
    fun extractStableKey_onlySlash_returnsNull() {
        assertNull(extractStableKey("/"))
    }

    @Test
    fun extractStableKey_onlyAuthTokenSegment_returnsNull() {
        assertNull(extractStableKey("/justOneSegment"))
    }

    @Test
    fun extractStableKey_tokenAndRemoteOnly_returnsRemote() {
        val key = extractStableKey("/token/RemoteOnly")
        assertEquals("RemoteOnly", key)
    }
}
