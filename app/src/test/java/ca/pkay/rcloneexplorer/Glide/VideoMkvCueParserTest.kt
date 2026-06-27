package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VideoMkvCueParserTest {

    @Test
    fun findEarliestClusterPosition_readsCueClusterPosition() {
        val clusterPos = 75_000_000L
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(clusterPos))
            assertEquals(clusterPos, VideoMkvCueParser.findEarliestClusterPosition(tail))
        } finally {
            tail.delete()
        }
    }

    @Test
    fun findEarliestClusterPosition_returnsSmallestCuePoint() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        try {
            Files.write(
                tail.toPath(),
                cuesElement(
                    90_000_000L,
                    60_000_000L,
                    120_000_000L,
                ),
            )
            assertEquals(60_000_000L, VideoMkvCueParser.findEarliestClusterPosition(tail))
        } finally {
            tail.delete()
        }
    }

    @Test
    fun findClusterDownloadStart_skipsWhenClusterInHead() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(60_000_000L))
            assertEquals(
                -1L,
                VideoAv1ThumbnailHelper.findClusterDownloadStart(
                    tail,
                    400_000_000L,
                    80_000_000L,
                    360_000_000L,
                ),
            )
        } finally {
            tail.delete()
        }
    }

    @Test
    fun findClusterDownloadStart_returnsGapClusterPosition() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(150_000_000L))
            assertEquals(
                150_000_000L,
                VideoAv1ThumbnailHelper.findClusterDownloadStart(
                    tail,
                    400_000_000L,
                    80_000_000L,
                    360_000_000L,
                ),
            )
        } finally {
            tail.delete()
        }
    }

    private fun cuesElement(vararg clusterPositions: Long): ByteArray {
        val cuePoints = clusterPositions.map { cuePoint(it) }.reduce { acc, bytes -> acc + bytes }
        val cuesBody = cuePoints
        val cuesSize = ebmlSize(cuesBody.size)
        return byteArrayOf(0x1C, 0x53, 0xBB, 0x6B) + cuesSize + cuesBody
    }

    private fun cuePoint(clusterPosition: Long): ByteArray {
        val clusterPosElement = byteArrayOf(0xF1.toByte()) + ebmlSize(valueVintBytes(clusterPosition).size) + valueVintBytes(clusterPosition)
        val body = clusterPosElement
        return byteArrayOf(0xBB.toByte()) + ebmlSize(body.size) + body
    }

    private fun valueVintBytes(value: Long): ByteArray {
        var remaining = value
        var width = 1
        while (remaining >= (1L shl (7 * width))) {
            width++
        }
        val bytes = ByteArray(width)
        var current = remaining
        for (i in width - 1 downTo 0) {
            bytes[i] = (current and 0xFF).toByte()
            current = current ushr 8
        }
        bytes[0] = (bytes[0].toInt() or (1 shl (8 - width))).toByte()
        return bytes
    }

    private fun ebmlSize(size: Int): ByteArray {
        assertTrue(size >= 0)
        return valueVintBytes(size.toLong())
    }
}
