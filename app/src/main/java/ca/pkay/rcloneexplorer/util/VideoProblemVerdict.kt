package ca.pkay.rcloneexplorer.util

/**
 * Classification for a probed video. Clean files are not exported (count only).
 */
enum class VideoProblemVerdict {
    SEEK,
    AUDIO,
    BOTH,
    PROBE_FAILED,
    ;

    companion object {
        fun fromFlags(audioProblem: Boolean, seekProblem: Boolean, probeFailed: Boolean): VideoProblemVerdict? {
            if (probeFailed) {
                return PROBE_FAILED
            }
            return when {
                audioProblem && seekProblem -> BOTH
                audioProblem -> AUDIO
                seekProblem -> SEEK
                else -> null
            }
        }
    }
}
