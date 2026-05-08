package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFolderPolicyTransitionsTest {

    private val remote = "myRemote"

    private fun row(path: String, thumb: Boolean, cache: Boolean) =
        MediaFolderPolicyRow(path = path, thumbnail = thumb, cache = cache)

    // region — empty / no-change cases

    @Test
    fun `empty prev and new produces no transitions`() {
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `identical rows produce no transitions`() {
        val rows = listOf(row("/Videos", thumb = true, cache = false))
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, rows, rows)
        assertTrue(result.isEmpty())
    }

    // endregion

    // region — false -> true (ON) transitions

    @Test
    fun `thumbnail false to true emits THUMBNAIL ON`() {
        val prev = listOf(row("/Videos", thumb = false, cache = false))
        val next = listOf(row("/Videos", thumb = true, cache = false))
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        assertEquals(1, result.size)
        assertEquals(TransitionEvent(remote, "/Videos", PolicyType.THUMBNAIL, turningOn = true), result[0])
    }

    @Test
    fun `cache false to true emits CACHE ON`() {
        val prev = listOf(row("/Videos", thumb = false, cache = false))
        val next = listOf(row("/Videos", thumb = false, cache = true))
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        assertEquals(1, result.size)
        assertEquals(TransitionEvent(remote, "/Videos", PolicyType.CACHE, turningOn = true), result[0])
    }

    @Test
    fun `new row with both flags ON emits two ON events`() {
        val prev = emptyList<MediaFolderPolicyRow>()
        val next = listOf(row("/Photos", thumb = true, cache = true))
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        assertEquals(2, result.size)
        assertTrue(result.any { it.type == PolicyType.THUMBNAIL && it.turningOn })
        assertTrue(result.any { it.type == PolicyType.CACHE && it.turningOn })
        assertTrue(result.all { it.remoteName == remote && it.folderPath == "/Photos" })
    }

    // endregion

    // region — true -> false (OFF) transitions

    @Test
    fun `thumbnail true to false emits THUMBNAIL OFF`() {
        val prev = listOf(row("/Videos", thumb = true, cache = false))
        val next = listOf(row("/Videos", thumb = false, cache = false))
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        // Row with both flags false is filtered out by writePolicyRows; transitions still computed.
        assertEquals(1, result.size)
        assertEquals(TransitionEvent(remote, "/Videos", PolicyType.THUMBNAIL, turningOn = false), result[0])
    }

    @Test
    fun `removed row emits OFF events for whichever flags were ON`() {
        val prev = listOf(row("/Videos", thumb = true, cache = true))
        val next = emptyList<MediaFolderPolicyRow>()
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        assertEquals(2, result.size)
        assertTrue(result.all { !it.turningOn })
        assertTrue(result.any { it.type == PolicyType.THUMBNAIL })
        assertTrue(result.any { it.type == PolicyType.CACHE })
    }

    @Test
    fun `removed row with only thumbnail ON emits only THUMBNAIL OFF`() {
        val prev = listOf(row("/Videos", thumb = true, cache = false))
        val next = emptyList<MediaFolderPolicyRow>()
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        assertEquals(1, result.size)
        assertEquals(TransitionEvent(remote, "/Videos", PolicyType.THUMBNAIL, turningOn = false), result[0])
    }

    // endregion

    // region — multiple rows / mixed transitions

    @Test
    fun `multiple rows produce independent transitions`() {
        val prev = listOf(
            row("/Videos", thumb = true, cache = false),
            row("/Photos", thumb = false, cache = true),
        )
        val next = listOf(
            row("/Videos", thumb = false, cache = true),
            row("/Photos", thumb = true, cache = true),
        )
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        // /Videos: thumb OFF, cache ON
        // /Photos: thumb ON, (cache unchanged)
        assertEquals(3, result.size)
        assertTrue(result.any { it.folderPath == "/Videos" && it.type == PolicyType.THUMBNAIL && !it.turningOn })
        assertTrue(result.any { it.folderPath == "/Videos" && it.type == PolicyType.CACHE && it.turningOn })
        assertTrue(result.any { it.folderPath == "/Photos" && it.type == PolicyType.THUMBNAIL && it.turningOn })
    }

    // endregion

    // region — path normalization

    @Test
    fun `paths are normalized before comparison`() {
        val prev = listOf(row("Videos/", thumb = true, cache = false))
        val next = listOf(row("/Videos", thumb = true, cache = false))
        // After normalization both are "/Videos" — no change
        val result = MediaFolderPolicyTransitions.computeTransitions(remote, prev, next)
        assertTrue(result.isEmpty())
    }

    // endregion
}
