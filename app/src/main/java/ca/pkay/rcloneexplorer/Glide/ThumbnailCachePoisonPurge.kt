package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.SyncLog
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-time cleanup of disk-cache entries that hold SVG / XML placeholders instead of raster
 * image bytes. Older builds cached whatever the serve path returned, so a folder of photos
 * could end up with hundreds of identical {@code icon-lite.svg} blobs that Glide cannot decode.
 * New fetches reject such payloads ([ThumbnailImageTranscoder]); this removes the legacy ones.
 */
object ThumbnailCachePoisonPurge {

    const val PREF_KEY_DONE = "thumbnail_cache_poison_purge_v1_done"
    private const val TAG = "ThumbPoisonPurge"
    private const val LOG_TITLE = "ThumbDiagDbg"
    private const val HEAD_BYTES = 64
    private const val ENTRY_SUFFIX = ".0"

    data class Result(val scanned: Int, val removed: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs the purge once per install on the IO dispatcher; safe to call on every launch. */
    @JvmStatic
    fun runOnceIfNeeded(context: Context) {
        val app = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        if (prefs.getBoolean(PREF_KEY_DONE, false)) {
            return
        }
        scope.launch {
            val cache = GlideDiskCacheHolder.get(app)
            if (cache == null) {
                prefs.edit().putBoolean(PREF_KEY_DONE, true).apply()
                return@launch
            }
            val result = purge(cache, prefs)
            SyncLog.info(
                app,
                LOG_TITLE,
                "event=poisonPurgeDone scanned=${result.scanned} removed=${result.removed}",
            )
        }
    }

    /** Scans committed entry files, removes poisoned ones through the cache, then sets the flag. */
    @JvmStatic
    fun purge(cache: ThumbnailDiskCache, prefs: SharedPreferences): Result {
        val entries = cache.directory.listFiles { file -> file.isFile && file.name.endsWith(ENTRY_SUFFIX) }
            ?: emptyArray()
        var removed = 0
        for (file in entries) {
            if (!isPoisoned(readHead(file))) {
                continue
            }
            if (cache.removeSafeKey(file.name.removeSuffix(ENTRY_SUFFIX))) {
                removed++
            }
        }
        prefs.edit().putBoolean(PREF_KEY_DONE, true).apply()
        return Result(scanned = entries.size, removed = removed)
    }

    /** True for payloads starting (after whitespace / UTF-8 BOM) with {@code <?xml} or {@code <svg}. */
    @JvmStatic
    fun isPoisoned(head: ByteArray): Boolean {
        var start = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            start = 3
        }
        while (start < head.size && head[start].toInt().toChar().isWhitespace()) {
            start++
        }
        val text = String(head, start, head.size - start, Charsets.ISO_8859_1).lowercase()
        return text.startsWith("<?xml") || text.startsWith("<svg")
    }

    private fun readHead(file: File): ByteArray = try {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    } catch (e: Exception) {
        FLog.w(TAG, "Could not read %s", e, file.name)
        ByteArray(0)
    }
}
