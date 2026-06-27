package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMkvClusterAlignTest {

    @Test
    fun alignClusterStart_usesLastClusterAtOrBeforeCandidate() {
        val probeStart = 80_000_000L
        val clusterAt = 80_500_000L
        val probe = ByteArray(1_048_576) { 0x00 }
        val rel = (clusterAt - probeStart).toInt()
        probe[rel] = 0x1F.toByte()
        probe[rel + 1] = 0x43
        probe[rel + 2] = 0xB6.toByte()
        probe[rel + 3] = 0x75

        assertEquals(
            clusterAt,
            VideoMkvClusterAlign.alignClusterStart(probe, probeStart, 80_600_000L),
        )
    }

    @Test
    fun alignClusterStart_fallsBackToCandidateWhenNoCluster() {
        val candidate = 85_352_318L
        val probe = ByteArray(512) { 0x00 }
        assertEquals(
            candidate,
            VideoMkvClusterAlign.alignClusterStart(probe, candidate - 256, candidate),
        )
    }

    @Test
    fun findLastClusterOffsetInProbe_picksLatestMarker() {
        val probeStart = 1_000L
        val probe = byteArrayOf(
            0x00, 0x1F, 0x43, 0xB6.toByte(), 0x75,
            0x00, 0x00, 0x00,
            0x1F, 0x43, 0xB6.toByte(), 0x75,
        )
        assertEquals(probeStart + 8, VideoMkvClusterAlign.findLastClusterOffsetInProbe(probe, probeStart))
    }
}
