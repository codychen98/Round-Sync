package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.Glide
import com.bumptech.glide.disklrucache.DiskLruCache
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Rebuilds a Glide [DiskLruCache] journal from restored `*.0` blobs.
 *
 * Config backup extracts raw cache files (including a possibly stale/inconsistent `journal`).
 * [DiskLruCache.open] only returns entries listed in the journal; orphans are treated as misses
 * and get overwritten by policy prefetch. A clean rebuild after import makes restored thumbs
 * visible to [ThumbnailDiskCacheEvictor.isCachedIn].
 */
object ThumbnailDiskCacheReconciler {

    private const val TAG = "ThumbDiskReconcile"
    private const val DISK_CACHE_VERSION = 1
    private const val DISK_CACHE_VALUE_COUNT = 1
    private const val DISK_CACHE_SIZE_BYTES = 500L * 1024L * 1024L
    private const val JOURNAL = "journal"
    private const val JOURNAL_TMP = "journal.tmp"
    private const val JOURNAL_BKP = "journal.bkp"
    private const val MAGIC = "libcore.io.DiskLruCache"
    private const val VERSION_1 = "1"
    private val VALUE_FILE_SUFFIX = ".0"

    data class Result(
        val registered: Int,
        val skippedInvalid: Int,
    )

    /**
     * Scans the canonical thumbnails directory, rewrites a CLEAN-only journal from on-disk
     * `*.0` files, validates via [DiskLruCache.open], and resets Glide if it already held an
     * open disk-cache handle against a pre-restore journal.
     */
    @JvmStatic
    fun reconcileAfterImport(context: Context): Result {
        val cacheDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext)
            ?: return Result(0, 0)
        val result = rebuildJournalFromValueFiles(cacheDir)
        resetGlideDiskCacheIfNeeded(context.applicationContext)
        return result
    }

    /**
     * Rewrites [JOURNAL] from non-empty `*.0` value files under [cacheDir].
     * Exported for unit tests.
     */
    @JvmStatic
    fun rebuildJournalFromValueFiles(cacheDir: File): Result {
        if (!cacheDir.isDirectory) {
            return Result(0, 0)
        }
        val valueFiles = cacheDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name.endsWith(VALUE_FILE_SUFFIX) &&
                    !file.name.contains('/') &&
                    file.length() > 0L
            }
            .orEmpty()
            .sortedBy { it.name }

        var skippedInvalid = 0
        val cleanLines = ArrayList<String>(valueFiles.size)
        for (file in valueFiles) {
            val key = file.name.removeSuffix(VALUE_FILE_SUFFIX)
            if (!isValidDiskLruKey(key)) {
                skippedInvalid++
                continue
            }
            cleanLines.add("CLEAN $key ${file.length()}")
        }

        deleteJournalSidecars(cacheDir)
        try {
            writeJournal(cacheDir, cleanLines)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to write rebuilt thumbnail journal", t)
            return Result(0, skippedInvalid + cleanLines.size)
        }

        return try {
            val cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES,
            )
            try {
                // Opening validates CLEAN lines against on-disk files and rewrites a clean journal
                // on close. Count keys that remain readable.
                var registered = 0
                for (line in cleanLines) {
                    val key = line.split(' ', limit = 3).getOrNull(1) ?: continue
                    if (cache.get(key) != null) {
                        registered++
                    }
                }
                Result(registered, skippedInvalid + (cleanLines.size - registered))
            } finally {
                try {
                    cache.close()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            FLog.w(TAG, "DiskLruCache rejected rebuilt journal; leaving value files in place", t)
            Result(0, skippedInvalid + cleanLines.size)
        }
    }

    /**
     * DiskLruCache keys are typically Glide safe-key hex (64 chars). Reject path separators and
     * empty names so a bad restore cannot write a corrupt journal line.
     */
    @JvmStatic
    fun isValidDiskLruKey(key: String): Boolean {
        if (key.isEmpty() || key.length > 120) {
            return false
        }
        return key.all { ch ->
            (ch in 'a'..'z') || (ch in '0'..'9') || ch == '_' || ch == '-'
        }
    }

    private fun deleteJournalSidecars(cacheDir: File) {
        for (name in listOf(JOURNAL, JOURNAL_TMP, JOURNAL_BKP)) {
            val file = File(cacheDir, name)
            if (file.exists() && !file.delete()) {
                FLog.w(TAG, "Failed to delete %s before rebuild", file.absolutePath)
            }
        }
    }

    private fun writeJournal(cacheDir: File, cleanLines: List<String>) {
        val journal = File(cacheDir, JOURNAL)
        BufferedWriter(
            OutputStreamWriter(FileOutputStream(journal), StandardCharsets.US_ASCII),
        ).use { writer ->
            writer.write(MAGIC)
            writer.write('\n'.code)
            writer.write(VERSION_1)
            writer.write('\n'.code)
            writer.write(DISK_CACHE_VERSION.toString())
            writer.write('\n'.code)
            writer.write(DISK_CACHE_VALUE_COUNT.toString())
            writer.write('\n'.code)
            writer.write('\n'.code)
            for (line in cleanLines) {
                writer.write(line)
                writer.write('\n'.code)
            }
        }
    }

    private fun resetGlideDiskCacheIfNeeded(context: Context) {
        try {
            if (Glide.isInitialized()) {
                Glide.tearDown()
                // Next Glide.get()/with() re-applies RoundSyncGlideModule against the rebuilt journal.
                Glide.get(context)
            }
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to reset Glide after thumbnail cache reconcile", t)
        }
    }
}
