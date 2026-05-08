package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [partitionCachedItems].
 * Uses String items so no Android framework classes are required.
 */
class GlideCacheCheckerTest {

    @Test
    fun partition_allCached_emptyToFetch() {
        val items = listOf("a", "b", "c")
        val (toFetch, alreadyCached) = partitionCachedItems(items) { true }
        assertTrue(toFetch.isEmpty())
        assertEquals(items, alreadyCached)
    }

    @Test
    fun partition_noneCached_allToFetch() {
        val items = listOf("a", "b", "c")
        val (toFetch, alreadyCached) = partitionCachedItems(items) { false }
        assertEquals(items, toFetch)
        assertTrue(alreadyCached.isEmpty())
    }

    @Test
    fun partition_mixed_splitsCorrectly() {
        val items = listOf("a", "b", "c", "d")
        val cached = setOf("b", "d")
        val (toFetch, alreadyCached) = partitionCachedItems(items) { it in cached }
        assertEquals(listOf("a", "c"), toFetch)
        assertEquals(listOf("b", "d"), alreadyCached)
    }

    @Test
    fun partition_emptyList_returnsEmptyPair() {
        val (toFetch, alreadyCached) = partitionCachedItems(emptyList<String>()) { true }
        assertTrue(toFetch.isEmpty())
        assertTrue(alreadyCached.isEmpty())
    }

    @Test
    fun partition_preservesOrder() {
        val items = listOf("x", "y", "z", "w")
        val (toFetch, _) = partitionCachedItems(items) { it == "y" }
        assertEquals(listOf("x", "z", "w"), toFetch)
    }
}
