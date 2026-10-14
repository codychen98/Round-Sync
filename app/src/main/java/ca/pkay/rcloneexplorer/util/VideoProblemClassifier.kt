package ca.pkay.rcloneexplorer.util

/**
 * Pure classification from probe facts (unit-tested without ExoPlayer).
 */
object VideoProblemClassifier {

    data class AudioTrackFact(
        val sampleMimeType: String,
        val language: String,
        val decodable: Boolean,
    )

    data class ProbeFacts(
        val audioTracks: List<AudioTrackFact>,
        val isSeekable: Boolean,
        val hasSeekInCurrentWindowCommand: Boolean,
        val probeFailed: Boolean = false,
        val failureDetail: String = "",
    )

    data class Classification(
        val verdict: VideoProblemVerdict?,
        val detail: String,
    )

    @JvmStatic
    fun classify(facts: ProbeFacts): Classification {
        if (facts.probeFailed) {
            val detail = facts.failureDetail.trim().ifEmpty { "probe_failed" }
            return Classification(VideoProblemVerdict.PROBE_FAILED, detail)
        }
        val audioTracks = facts.audioTracks
        val audioProblem = audioTracks.isNotEmpty() && audioTracks.none { it.decodable }
        val seekProblem = !VideoSeekPolicy.canSeekInCurrentWindow(
            facts.isSeekable,
            facts.hasSeekInCurrentWindowCommand,
        )
        val verdict = VideoProblemVerdict.fromFlags(audioProblem, seekProblem, probeFailed = false)
        val detail = buildDetail(audioTracks, audioProblem, seekProblem)
        return Classification(verdict, detail)
    }

    private fun buildDetail(
        audioTracks: List<AudioTrackFact>,
        audioProblem: Boolean,
        seekProblem: Boolean,
    ): String {
        val parts = ArrayList<String>(4)
        if (audioProblem) {
            val first = audioTracks.firstOrNull { !it.decodable } ?: audioTracks.first()
            val mime = first.sampleMimeType.ifEmpty { "unknown" }
            val lang = first.language.trim()
            parts.add(if (lang.isEmpty()) mime else "$mime $lang")
        }
        if (seekProblem) {
            parts.add("not_seekable")
        }
        return parts.joinToString(" ")
    }
}
