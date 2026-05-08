package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ThumbnailPrefetchTargets.filterByBlacklistSet], which backs
 * [ThumbnailPrefetchTargets.filterOutBlacklisted].
 *
 * Uses [BlacklistKey] as the item type to avoid constructing [ca.pkay.rcloneexplorer.Items.FileItem]
 * (which pulls Android APIs). The identity lambda `keyOf = { it }` drives filtering directly.
 */
class ThumbnailPrefetchTargetsBlacklistTest {

    private fun key(path: String, size: Long = 100L, mtime: Long = 200L) =
        BlacklistKey("myRemote", path, size, mtime)

    // --- no blacklist ---

    @Test
    fun emptyBlacklistSet_returnsAllItems() {
        val items = listOf(key("a.mkv"), key("b.mkv"), key("c.mkv"))
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(items, emptySet()) { it }
        assertEquals(items, result)
    }

    @Test
    fun emptyItems_returnsEmpty() {
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(
            emptyList<BlacklistKey>(),
            setOf(key("x.mkv").encode()),
        ) { it }
        assertTrue(result.isEmpty())
    }

    // --- matching blacklist ---

    @Test
    fun blacklistedItem_isRemoved() {
        val keep1 = key("keep1.mkv")
        val keep2 = key("keep2.mkv")
        val blacklisted = key("blacklisted.mkv", size = 999L, mtime = 888L)
        val blacklistSet = setOf(blacklisted.encode())
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(
            listOf(keep1, blacklisted, keep2),
            blacklistSet,
        ) { it }
        assertEquals(listOf(keep1, keep2), result)
    }

    @Test
    fun allBlacklisted_returnsEmpty() {
        val items = listOf(key("a.mkv"), key("b.mkv"))
        val blacklistSet = items.map { it.encode() }.toSet()
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(items, blacklistSet) { it }
        assertTrue(result.isEmpty())
    }

    // --- stale blacklist (different mtime) ---

    @Test
    fun staleMtime_itemNotFiltered() {
        val original = key("file.mkv", size = 100L, mtime = 200L)
        val reuploaded = key("file.mkv", size = 100L, mtime = 201L)
        val blacklistSet = setOf(original.encode())
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(
            listOf(reuploaded),
            blacklistSet,
        ) { it }
        assertEquals(listOf(reuploaded), result)
    }

    @Test
    fun staleSize_itemNotFiltered() {
        val original = key("file.mkv", size = 100L, mtime = 200L)
        val reuploaded = key("file.mkv", size = 101L, mtime = 200L)
        val blacklistSet = setOf(original.encode())
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(
            listOf(reuploaded),
            blacklistSet,
        ) { it }
        assertEquals(listOf(reuploaded), result)
    }

    // --- ordering preserved ---

    @Test
    fun resultOrderMatchesInputOrder() {
        val items = listOf(key("z.mkv"), key("a.mkv"), key("m.mkv"))
        val blacklistSet = setOf(key("m.mkv").encode())
        val result = ThumbnailPrefetchTargets.filterByBlacklistSet(items, blacklistSet) { it }
        assertEquals(listOf(key("z.mkv"), key("a.mkv")), result)
    }
}
