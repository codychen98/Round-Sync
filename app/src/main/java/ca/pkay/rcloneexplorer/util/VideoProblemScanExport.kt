package ca.pkay.rcloneexplorer.util

/**
 * Plain-text export for problem-file scan results.
 * Format: `<VERDICT>\t<relative path>\t<detail>` plus a `# remux:` suggestion line
 * for SEEK, AUDIO, and BOTH.
 */
object VideoProblemScanExport {

    data class ProblemRow(
        val verdict: VideoProblemVerdict,
        val relativePath: String,
        val detail: String,
    )

    @JvmStatic
    fun formatReport(rows: List<ProblemRow>): String {
        if (rows.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (row in rows) {
            val path = scrub(row.relativePath)
            val detail = scrub(row.detail)
            sb.append(row.verdict.name)
                .append('\t')
                .append(path)
                .append('\t')
                .append(detail)
                .append('\n')
            if (row.verdict != VideoProblemVerdict.PROBE_FAILED) {
                sb.append("# remux: ")
                    .append(suggestedRemuxCommand(row.verdict, path))
                    .append('\n')
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun suggestedRemuxCommand(verdict: VideoProblemVerdict, relativePath: String): String {
        val input = quotePath(relativePath)
        val output = quotePath(suggestedOutputPath(relativePath, verdict))
        return when (verdict) {
            VideoProblemVerdict.AUDIO, VideoProblemVerdict.BOTH ->
                "ffmpeg -i $input -map 0 -c copy -c:a flac $output"
            VideoProblemVerdict.SEEK -> seekRemuxCommand(relativePath, input, output)
            VideoProblemVerdict.PROBE_FAILED -> ""
        }
    }

    private fun seekRemuxCommand(relativePath: String, input: String, output: String): String {
        val lower = relativePath.lowercase()
        return if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")) {
            "ffmpeg -i $input -c copy -movflags +faststart $output"
        } else {
            "ffmpeg -i $input -c copy -reserve_index_space 200k $output"
        }
    }

    private fun suggestedOutputPath(relativePath: String, verdict: VideoProblemVerdict): String {
        val slash = relativePath.lastIndexOf('/')
        val dir = if (slash >= 0) relativePath.substring(0, slash + 1) else ""
        val name = if (slash >= 0) relativePath.substring(slash + 1) else relativePath
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val suffix = when (verdict) {
            VideoProblemVerdict.AUDIO -> "_audio_fixed"
            VideoProblemVerdict.SEEK -> "_seek_fixed"
            VideoProblemVerdict.BOTH -> "_fixed"
            VideoProblemVerdict.PROBE_FAILED -> "_fixed"
        }
        return dir + base + suffix + ext
    }

    private fun quotePath(path: String): String {
        val escaped = path.replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun scrub(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
}
