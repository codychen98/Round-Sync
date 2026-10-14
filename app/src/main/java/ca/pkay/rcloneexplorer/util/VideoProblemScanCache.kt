package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists per-file probe results keyed by (remote, path, size, modtime) so rescans skip
 * unchanged files. Also stores the last completed scan report for the results UI.
 */
object VideoProblemScanCache {

    private const val PREF_ENTRIES = "video_problem_scan_cache_v1"
    private const val PREF_LAST_REPORT = "video_problem_scan_last_report_v1"

    data class CachedEntry(
        val remote: String,
        val path: String,
        val size: Long,
        val modTime: Long,
        val verdict: VideoProblemVerdict?,
        val detail: String,
    )

    data class LastReport(
        val remote: String,
        val rootPath: String,
        val scannedCount: Int,
        val skippedUnchanged: Int,
        val problemRows: List<VideoProblemScanExport.ProblemRow>,
        val finishedAtEpochMs: Long,
    )

    fun cacheKey(remote: String, path: String, size: Long, modTime: Long): String =
        listOf(remote, path, size.toString(), modTime.toString()).joinToString("\u0001")

    fun readEntry(
        prefs: SharedPreferences,
        remote: String,
        path: String,
        size: Long,
        modTime: Long,
    ): CachedEntry? {
        val map = readAllEntries(prefs)
        return map[cacheKey(remote, path, size, modTime)]
    }

    fun putEntry(prefs: SharedPreferences, entry: CachedEntry) {
        val map = readAllEntries(prefs).toMutableMap()
        map[cacheKey(entry.remote, entry.path, entry.size, entry.modTime)] = entry
        writeAllEntries(prefs, map.values)
    }

    fun putEntries(prefs: SharedPreferences, entries: Collection<CachedEntry>) {
        if (entries.isEmpty()) {
            return
        }
        val map = readAllEntries(prefs).toMutableMap()
        for (entry in entries) {
            map[cacheKey(entry.remote, entry.path, entry.size, entry.modTime)] = entry
        }
        writeAllEntries(prefs, map.values)
    }

    fun readLastReport(prefs: SharedPreferences): LastReport? {
        val raw = prefs.getString(PREF_LAST_REPORT, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val rowsJson = obj.getJSONArray("rows")
            val rows = ArrayList<VideoProblemScanExport.ProblemRow>(rowsJson.length())
            for (i in 0 until rowsJson.length()) {
                val r = rowsJson.getJSONObject(i)
                val verdict = VideoProblemVerdict.valueOf(r.getString("verdict"))
                rows.add(
                    VideoProblemScanExport.ProblemRow(
                        verdict = verdict,
                        relativePath = r.getString("path"),
                        detail = r.optString("detail", ""),
                    ),
                )
            }
            LastReport(
                remote = obj.getString("remote"),
                rootPath = obj.getString("rootPath"),
                scannedCount = obj.optInt("scannedCount", 0),
                skippedUnchanged = obj.optInt("skippedUnchanged", 0),
                problemRows = rows,
                finishedAtEpochMs = obj.optLong("finishedAtEpochMs", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun writeLastReport(prefs: SharedPreferences, report: LastReport) {
        val rowsJson = JSONArray()
        for (row in report.problemRows) {
            rowsJson.put(
                JSONObject()
                    .put("verdict", row.verdict.name)
                    .put("path", row.relativePath)
                    .put("detail", row.detail),
            )
        }
        val obj = JSONObject()
            .put("remote", report.remote)
            .put("rootPath", report.rootPath)
            .put("scannedCount", report.scannedCount)
            .put("skippedUnchanged", report.skippedUnchanged)
            .put("finishedAtEpochMs", report.finishedAtEpochMs)
            .put("rows", rowsJson)
        prefs.edit().putString(PREF_LAST_REPORT, obj.toString()).apply()
    }

    private fun readAllEntries(prefs: SharedPreferences): Map<String, CachedEntry> {
        val raw = prefs.getString(PREF_ENTRIES, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val out = LinkedHashMap<String, CachedEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val remote = o.getString("remote")
                val path = o.getString("path")
                val size = o.getLong("size")
                val modTime = o.getLong("modTime")
                val verdictName = o.optString("verdict", "")
                val verdict = if (verdictName.isEmpty()) {
                    null
                } else {
                    try {
                        VideoProblemVerdict.valueOf(verdictName)
                    } catch (_: Exception) {
                        null
                    }
                }
                val entry = CachedEntry(
                    remote = remote,
                    path = path,
                    size = size,
                    modTime = modTime,
                    verdict = verdict,
                    detail = o.optString("detail", ""),
                )
                out[cacheKey(remote, path, size, modTime)] = entry
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeAllEntries(prefs: SharedPreferences, entries: Collection<CachedEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject()
                    .put("remote", entry.remote)
                    .put("path", entry.path)
                    .put("size", entry.size)
                    .put("modTime", entry.modTime)
                    .put("verdict", entry.verdict?.name ?: "")
                    .put("detail", entry.detail),
            )
        }
        prefs.edit().putString(PREF_ENTRIES, arr.toString()).apply()
    }
}
