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
    fun findEarliestClusterPosition_appliesSegmentOffset() {
        val segmentStart = 1_000L
        val clusterInSegment = 75_000_000L
        val head = File.createTempFile("mkv_head_test_", ".bin")
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        try {
            Files.write(head.toPath(), ByteArray(segmentStart.toInt()) { 0x00 } + segmentElement())
            Files.write(tail.toPath(), cuesElement(clusterInSegment))
            assertEquals(
                segmentStart + clusterInSegment,
                VideoMkvCueParser.findEarliestClusterPosition(head, tail),
            )
        } finally {
            head.delete()
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
    fun findCuesByteOffset_readsSeekHead() {
        val cuesOffsetInSegment = 300_000_000L
        val head = File.createTempFile("mkv_head_test_", ".bin")
        try {
            Files.write(
                head.toPath(),
                segmentElement() + seekHeadElement(cuesOffsetInSegment),
            )
            assertEquals(
                cuesOffsetInSegment,
                VideoMkvCueParser.findCuesByteOffset(head),
            )
        } finally {
            head.delete()
        }
    }

    @Test
    fun findClusterDownloadStart_skipsWhenClusterInHead() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        val head = File.createTempFile("mkv_head_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(60_000_000L))
            Files.write(head.toPath(), byteArrayOf())
            assertEquals(
                -1L,
                VideoAv1ThumbnailHelper.findClusterDownloadStart(
                    "http://example.test/file.mkv",
                    head,
                    tail,
                    400_000_000L,
                    80_000_000L,
                    360_000_000L,
                ),
            )
        } finally {
            tail.delete()
            head.delete()
        }
    }

    @Test
    fun findClusterDownloadStart_returnsGapClusterPosition() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        val head = File.createTempFile("mkv_head_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(150_000_000L))
            Files.write(head.toPath(), byteArrayOf())
            assertEquals(
                150_000_000L,
                VideoAv1ThumbnailHelper.findClusterDownloadStart(
                    "http://example.test/file.mkv",
                    head,
                    tail,
                    400_000_000L,
                    80_000_000L,
                    360_000_000L,
                ),
            )
        } finally {
            tail.delete()
            head.delete()
        }
    }

    @Test
    fun findClusterDownloadStart_fallsBackToGapPercentileWhenCuesMissing() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        val head = File.createTempFile("mkv_head_test_", ".bin")
        try {
            Files.write(tail.toPath(), byteArrayOf(0x01, 0x02, 0x03))
            Files.write(head.toPath(), byteArrayOf())
            val fileSize = 400_000_000L
            val headBytes = 80_000_000L
            val tailStart = 360_000_000L
            assertEquals(
                fileSize * 25 / 100,
                VideoAv1ThumbnailHelper.findClusterDownloadStart(
                    "http://example.test/file.mkv",
                    head,
                    tail,
                    fileSize,
                    headBytes,
                    tailStart,
                ),
            )
        } finally {
            tail.delete()
            head.delete()
        }
    }

    @Test
    fun findClusterDownloadCandidates_returnsMultipleCuePositionsInGap() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        val head = File.createTempFile("mkv_head_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(150_000_000L, 220_000_000L, 90_000_000L))
            Files.write(head.toPath(), byteArrayOf())
            val candidates = VideoAv1ThumbnailHelper.findClusterDownloadCandidates(
                "http://example.test/file.mkv",
                head,
                tail,
                400_000_000L,
                80_000_000L,
                360_000_000L,
            )
            assertTrue(candidates.contains(90_000_000L))
            assertTrue(candidates.contains(150_000_000L))
            assertTrue(candidates.size >= 2)
        } finally {
            tail.delete()
            head.delete()
        }
    }

    @Test
    fun findAllClusterPositions_returnsSortedUniqueCuePositions() {
        val tail = File.createTempFile("mkv_cue_test_", ".bin")
        try {
            Files.write(tail.toPath(), cuesElement(120_000_000L, 60_000_000L, 120_000_000L))
            assertEquals(
                listOf(60_000_000L, 120_000_000L),
                VideoMkvCueParser.findAllClusterPositions(null, tail),
            )
        } finally {
            tail.delete()
        }
    }

    private fun segmentElement(): ByteArray {
        return byteArrayOf(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF.toByte(), 0x00)
    }

    private fun seekHeadElement(cuesOffsetInSegment: Long): ByteArray {
        val seekId = byteArrayOf(0x53, 0xAB.toByte()) + ebmlSize(4) + byteArrayOf(
            0x1C,
            0x53,
            0xBB.toByte(),
            0x6B,
        )
        val seekPosition = byteArrayOf(0x53, 0xAC.toByte()) +
            ebmlSize(valueVintBytes(cuesOffsetInSegment).size) +
            valueVintBytes(cuesOffsetInSegment)
        val seekBody = seekId + seekPosition
        val seek = byteArrayOf(0x4D, 0xBB.toByte()) + ebmlSize(seekBody.size) + seekBody
        return byteArrayOf(0x11, 0x4D, 0x9B.toByte(), 0x74) + ebmlSize(seek.size) + seek
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
