package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProblemScanExportTest {

    @Test
    fun exportIncludesRemuxForAudioSeekAndBoth() {
        val report = VideoProblemScanExport.formatReport(
            listOf(
                VideoProblemScanExport.ProblemRow(
                    VideoProblemVerdict.AUDIO,
                    "show/ep01.mkv",
                    "audio/x-ms-wma jpn",
                ),
                VideoProblemScanExport.ProblemRow(
                    VideoProblemVerdict.SEEK,
                    "clip.mp4",
                    "not_seekable",
                ),
                VideoProblemScanExport.ProblemRow(
                    VideoProblemVerdict.BOTH,
                    "other.mkv",
                    "audio/x-ms-wma jpn not_seekable",
                ),
                VideoProblemScanExport.ProblemRow(
                    VideoProblemVerdict.PROBE_FAILED,
                    "bad.ts",
                    "prepare_timeout",
                ),
            ),
        )
        assertTrue(report.contains("AUDIO\tshow/ep01.mkv\taudio/x-ms-wma jpn"))
        assertTrue(report.contains("# remux: ffmpeg -i \"show/ep01.mkv\" -map 0 -c copy -c:a flac"))
        assertTrue(report.contains("SEEK\tclip.mp4\tnot_seekable"))
        assertTrue(report.contains("# remux: ffmpeg -i \"clip.mp4\" -c copy -movflags +faststart"))
        assertTrue(report.contains("BOTH\tother.mkv\t"))
        assertTrue(report.contains("# remux: ffmpeg -i \"other.mkv\" -map 0 -c copy -c:a flac"))
        assertTrue(report.contains("PROBE_FAILED\tbad.ts\tprepare_timeout"))
        assertFalse(report.contains("bad.ts\" -map 0"))
    }

    @Test
    fun mkvSeekUsesReserveIndexSpace() {
        val cmd = VideoProblemScanExport.suggestedRemuxCommand(
            VideoProblemVerdict.SEEK,
            "a/b.mkv",
        )
        assertTrue(cmd.contains("-reserve_index_space 200k"))
    }
}
