package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * Pure-JVM tests for [SimpleCacheRangeWriter.commitRangesInternal].
 *
 * Tests target the internal [RangeWriter] overload so no Media3 [Cache] / [CacheSpan] types
 * are needed on the JVM test classpath.
 */
class SimpleCacheRangeWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- helpers ---

    private fun makeSrcFile(size: Int, fill: Byte = 0xAB.toByte()): RandomAccessFile {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size.toLong())
            val buf = ByteArray(size) { fill }
            raf.seek(0)
            raf.write(buf)
        }
        return RandomAccessFile(file, "r")
    }

    private data class WriteCall(val offset: Long, val bytes: ByteArray)

    private fun capturingWriter(results: MutableList<WriteCall>, returns: Boolean = true) =
        SimpleCacheRangeWriter.RangeWriter { offset, bytes ->
            results.add(WriteCall(offset, bytes.copyOf()))
            returns
        }

    // --- commitRangesInternal basic behaviour ---

    @Test
    fun emptyRanges_returnsFalse() {
        val src = makeSrcFile(1024)
        assertFalse(SimpleCacheRangeWriter.commitRangesInternal(
            capturingWriter(mutableListOf()), emptyList(), src))
    }

    @Test
    fun singleRange_readsCorrectOffsetAndLength() {
        val totalSize = 1024
        val fill: Byte = 0x7F
        val src = makeSrcFile(totalSize, fill)
        val calls = mutableListOf<WriteCall>()

        val range = 100L..199L  // 100 bytes starting at offset 100
        SimpleCacheRangeWriter.commitRangesInternal(capturingWriter(calls), listOf(range), src)

        assertEquals(1, calls.size)
        assertEquals(100L, calls[0].offset)
        assertEquals(100, calls[0].bytes.size)
        assertTrue("All bytes should match fill value", calls[0].bytes.all { it == fill })
    }

    @Test
    fun twoRanges_bothIssued_correctOffsets() {
        val fill: Byte = 0x55
        val src = makeSrcFile(1024, fill)
        val calls = mutableListOf<WriteCall>()

        val headRange = 0L..255L    // 256 bytes at start
        val tailRange = 512L..767L  // 256 bytes in middle

        SimpleCacheRangeWriter.commitRangesInternal(capturingWriter(calls), listOf(headRange, tailRange), src)

        assertEquals(2, calls.size)
        assertEquals(0L, calls[0].offset)
        assertEquals(256, calls[0].bytes.size)
        assertEquals(512L, calls[1].offset)
        assertEquals(256, calls[1].bytes.size)
    }

    @Test
    fun returnsTrue_whenAtLeastOneRangeSucceeds() {
        val src = makeSrcFile(256)
        var callCount = 0
        val writer = SimpleCacheRangeWriter.RangeWriter { _, _ ->
            callCount++
            callCount == 1  // only the first call returns true
        }
        val ranges = listOf(0L..63L, 64L..127L)
        assertTrue(SimpleCacheRangeWriter.commitRangesInternal(writer, ranges, src))
    }

    @Test
    fun returnsFalse_whenAllRangesFail() {
        val src = makeSrcFile(256)
        val writer = SimpleCacheRangeWriter.RangeWriter { _, _ -> false }
        val ranges = listOf(0L..63L, 64L..127L)
        assertFalse(SimpleCacheRangeWriter.commitRangesInternal(writer, ranges, src))
    }

    @Test
    fun writerException_skipsRangeAndContinues() {
        val src = makeSrcFile(256)
        val successCalls = mutableListOf<WriteCall>()
        var callCount = 0
        val writer = SimpleCacheRangeWriter.RangeWriter { offset, bytes ->
            callCount++
            if (callCount == 1) throw RuntimeException("simulated failure")
            successCalls.add(WriteCall(offset, bytes.copyOf()))
            true
        }
        val ranges = listOf(0L..63L, 64L..127L)
        // First range throws, second should still succeed → overall true
        assertTrue(SimpleCacheRangeWriter.commitRangesInternal(writer, ranges, src))
        assertEquals(1, successCalls.size)
        assertEquals(64L, successCalls[0].offset)
    }

    @Test
    fun sparseRangeArithmetic_headAndTailMatchExtractorConstants() {
        val headBytes = SparseLocalThumbnailExtractor.HEAD_BYTES
        val tailBytes = SparseLocalThumbnailExtractor.TAIL_BYTES
        val fileSize = 1_073_741_824L  // 1 GiB

        val headRange = 0L until headBytes
        val tailRange = (fileSize - tailBytes) until fileSize

        assertEquals(0L, headRange.first)
        assertEquals(headBytes - 1, headRange.last)
        assertEquals(headBytes, headRange.last - headRange.first + 1)

        assertEquals(fileSize - tailBytes, tailRange.first)
        assertEquals(fileSize - 1, tailRange.last)
        assertEquals(tailBytes, tailRange.last - tailRange.first + 1)

        assertTrue("Ranges must not overlap", headRange.last < tailRange.first)
    }

    @Test
    fun zeroLengthRange_isSkipped() {
        val src = makeSrcFile(64)
        val calls = mutableListOf<WriteCall>()
        val zeroRange = 10L..9L  // last < first → length = 0
        SimpleCacheRangeWriter.commitRangesInternal(capturingWriter(calls), listOf(zeroRange), src)
        assertTrue("Zero-length range must produce no write call", calls.isEmpty())
    }
}
