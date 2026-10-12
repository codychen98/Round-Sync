package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.SyncLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-shot in-place shrink of oversized / opaque-PNG thumbnail DATA entries.
 *
 * Rewrites the same DiskLruCache filenames (same keys) so Glide and backup export keep working
 * without re-downloading. Journal size fields are left unchanged on purpose: after compaction the
 * cache under-uses its byte budget (safe direction). Do not hand-edit the journal.
 *
 * Never opens a second [com.bumptech.glide.disklrucache.DiskLruCache]; rewrites run under
 * [ThumbnailDiskCache.withExclusiveAccess] on the shared [GlideDiskCacheHolder] instance.
 */
object ThumbnailCacheCompactor {

    const val PREF_KEY_DONE = "thumbnail_cache_compaction_v1_done"
    private const val TAG = "ThumbCacheCompactor"
    private const val LOG_TITLE = "ThumbCompactDbg"
    private val ENTRY_NAME = Regex("^[0-9a-f]{64}\\.0$")

    data class Result(
        val scanned: Int,
        val rewritten: Int,
        val bytesBefore: Long,
        val bytesAfter: Long,
        val errors: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs compaction once per install on the IO dispatcher; safe to call on every launch. */
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
            val result = compact(cache, prefs)
            SyncLog.info(
                app,
                LOG_TITLE,
                "event=compactDone scanned=${result.scanned} rewritten=${result.rewritten}" +
                    " bytesBefore=${result.bytesBefore} bytesAfter=${result.bytesAfter}" +
                    " errors=${result.errors}",
            )
        }
    }

    /**
     * Compacts entry files under [cache]'s directory. Sets [PREF_KEY_DONE] only after a full
     * sweep completes so an interrupted run safely re-runs (already-shrunk JPEG ≤1280 are skipped).
     */
    @JvmStatic
    fun compact(cache: ThumbnailDiskCache, prefs: SharedPreferences): Result {
        return cache.withExclusiveAccess {
            compactLocked(cache.directory, prefs)
        }
    }

    private fun compactLocked(dir: File, prefs: SharedPreferences): Result {
        val entries = dir.listFiles { file ->
            file.isFile && ENTRY_NAME.matches(file.name)
        } ?: emptyArray()
        var rewritten = 0
        var bytesBefore = 0L
        var bytesAfter = 0L
        var errors = 0
        for (file in entries) {
            val before = file.length()
            bytesBefore += before
            when (val outcome = compactFile(file)) {
                CompactOutcome.UNCHANGED -> bytesAfter += before
                CompactOutcome.REWRITTEN -> {
                    rewritten++
                    bytesAfter += file.length()
                }
                CompactOutcome.ERROR -> {
                    errors++
                    bytesAfter += file.length()
                }
            }
        }
        prefs.edit().putBoolean(PREF_KEY_DONE, true).apply()
        return Result(
            scanned = entries.size,
            rewritten = rewritten,
            bytesBefore = bytesBefore,
            bytesAfter = bytesAfter,
            errors = errors,
        )
    }

    private enum class CompactOutcome { UNCHANGED, REWRITTEN, ERROR }

    private fun compactFile(file: File): CompactOutcome {
        val original = try {
            FileInputStream(file).use { it.readBytes() }
        } catch (e: Exception) {
            FLog.w(TAG, "Could not read %s", e, file.name)
            return CompactOutcome.ERROR
        }
        val compacted = try {
            ThumbnailImageTranscoder.transcode(original)
        } catch (e: Exception) {
            FLog.w(TAG, "Transcode failed for %s", e, file.name)
            return CompactOutcome.ERROR
        } catch (e: OutOfMemoryError) {
            FLog.w(TAG, "Transcode OOM for %s", e, file.name)
            return CompactOutcome.ERROR
        }
        if (compacted == null || Arrays.equals(compacted, original)) {
            return CompactOutcome.UNCHANGED
        }
        return if (atomicReplace(file, compacted)) {
            CompactOutcome.REWRITTEN
        } else {
            CompactOutcome.ERROR
        }
    }

    private fun atomicReplace(file: File, bytes: ByteArray): Boolean {
        val tmp = File(file.parentFile, "${file.name}.compact.tmp")
        return try {
            FileOutputStream(tmp).use { it.write(bytes) }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (e: Exception) {
            FLog.w(TAG, "Write failed for %s", e, file.name)
            false
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                FLog.w(TAG, "Could not delete temp %s", tmp.name)
            }
        }
    }
}
