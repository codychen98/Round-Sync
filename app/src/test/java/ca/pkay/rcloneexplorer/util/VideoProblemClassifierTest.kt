package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProblemClassifierTest {

    @Test
    fun cleanWhenDecodableAndSeekable() {
        val result = VideoProblemClassifier.classify(
            VideoProblemClassifier.ProbeFacts(
                audioTracks = listOf(
                    VideoProblemClassifier.AudioTrackFact("audio/mp4a-latm", "jpn", true),
                ),
                isSeekable = true,
                hasSeekInCurrentWindowCommand = true,
            ),
        )
        assertNull(result.verdict)
    }

    @Test
    fun audioWhenTracksExistButNoneDecodable() {
        val result = VideoProblemClassifier.classify(
            VideoProblemClassifier.ProbeFacts(
                audioTracks = listOf(
                    VideoProblemClassifier.AudioTrackFact("audio/x-ms-wma", "jpn", false),
                ),
                isSeekable = true,
                hasSeekInCurrentWindowCommand = true,
            ),
        )
        assertEquals(VideoProblemVerdict.AUDIO, result.verdict)
        assertTrue(result.detail.contains("audio/x-ms-wma"))
        assertTrue(result.detail.contains("jpn"))
    }

    @Test
    fun noAudioTrackIsNotAudioProblem() {
        val result = VideoProblemClassifier.classify(
            VideoProblemClassifier.ProbeFacts(
                audioTracks = emptyList(),
                isSeekable = true,
                hasSeekInCurrentWindowCommand = true,
            ),
        )
        assertNull(result.verdict)
    }

    @Test
    fun seekWhenNotSeekable() {
        val result = VideoProblemClassifier.classify(
            VideoProblemClassifier.ProbeFacts(
                audioTracks = listOf(
                    VideoProblemClassifier.AudioTrackFact("audio/mp4a-latm", "eng", true),
                ),
                isSeekable = false,
                hasSeekInCurrentWindowCommand = true,
            ),
        )
        assertEquals(VideoProblemVerdict.SEEK, result.verdict)
        assertTrue(result.detail.contains("not_seekable"))
    }

    @Test
    fun bothWhenAudioAndSeekBad() {
        val result = VideoProblemClassifier.classify(
            VideoProblemClassifier.ProbeFacts(
                audioTracks = listOf(
                    VideoProblemClassifier.AudioTrackFact("audio/x-ms-wma", "jpn", false),
                ),
                isSeekable = false,
                hasSeekInCurrentWindowCommand = false,
            ),
        )
        assertEquals(VideoProblemVerdict.BOTH, result.verdict)
    }

    @Test
    fun probeFailed() {
        val result = VideoProblemClassifier.classify(
            VideoProblemClassifier.ProbeFacts(
                audioTracks = emptyList(),
                isSeekable = false,
                hasSeekInCurrentWindowCommand = false,
                probeFailed = true,
                failureDetail = "prepare_timeout",
            ),
        )
        assertEquals(VideoProblemVerdict.PROBE_FAILED, result.verdict)
        assertEquals("prepare_timeout", result.detail)
    }
}
