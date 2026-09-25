package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.Uri
import ca.pkay.rcloneexplorer.Glide.ThumbnailDiskCacheReconciler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Restores thumbnail and media-cache blobs from a config export zip `cache/` tree into
 * [CanonicalCachePathResolver] locations. Missing `cache/` entries are ignored (older exports).
 *
 * After thumbnail blobs are extracted, [ThumbnailDiskCacheReconciler] rebuilds the Glide
 * DiskLruCache journal so policy prefetch treats restored files as cache hits.
 */
object CacheArchiveImporter {

    private const val TAG = "CacheArchiveImporter"
    private const val COPY_BUFFER_BYTES = 8192

    data class Result(
        val extracted: Int,
        val skipped: Int,
        val failed: Int,
        val reconciled: Int = 0,
    )

    private enum class Outcome {
        EXTRACTED,
        SKIPPED,
        FAILED,
        IGNORED,
    }

    @JvmStatic
    fun extractFromZip(context: Context, uri: Uri): Result {
        val app = context.applicationContext
        // Prefer a real filesystem path when the URI resolves to one so ZipFile can read
        // central-directory sizes for skip decisions. Otherwise stream with best-effort skip.
        val localPath = uri.path
        if (localPath != null && "file".equals(uri.scheme, ignoreCase = true)) {
            val file = File(localPath)
            if (file.isFile) {
                return extractFromZipFile(app, file)
            }
        }
        val inputStream = openUriInputStream(app, uri) ?: return Result(0, 0, 0)
        return finishExtract(app, extractFromZipStream(app, inputStream))
    }

    @JvmStatic
    fun extractFromZipFile(context: Context, file: File): Result {
        return try {
            ZipFile(file).use { zipFile ->
                finishExtract(
                    context.applicationContext,
                    extractFromZipFileHandle(context.applicationContext, zipFile),
                )
            }
        } catch (t: IOException) {
            FLog.w(TAG, "Could not open import file", t)
            Result(0, 0, 0)
        } catch (t: Throwable) {
            FLog.w(TAG, "Cache import interrupted", t)
            Result(0, 0, 0)
        }
    }

    private fun finishExtract(context: Context, partial: Result): Result {
        if (partial.extracted == 0 && partial.skipped == 0) {
            return partial
        }
        val reconcile = ThumbnailDiskCacheReconciler.reconcileAfterImport(context)
        FLog.i(
            TAG,
            "Thumbnail cache reconcile after import: registered=%d skippedInvalid=%d",
            reconcile.registered,
            reconcile.skippedInvalid,
        )
        return partial.copy(reconciled = reconcile.registered)
    }

    private fun extractFromZipFileHandle(context: Context, zipFile: ZipFile): Result {
        var extracted = 0
        var skipped = 0
        var failed = 0
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) {
                continue
            }
            when (
                processEntry(
                    context,
                    entry.name,
                    entry.size,
                    entry.time,
                    openStream = { zipFile.getInputStream(entry) },
                    sharedZipInputStream = false,
                )
            ) {
                Outcome.EXTRACTED -> extracted++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
                Outcome.IGNORED -> {}
            }
        }
        return Result(extracted, skipped, failed)
    }

    private fun extractFromZipStream(context: Context, inputStream: InputStream): Result {
        val app = context.applicationContext
        var extracted = 0
        var skipped = 0
        var failed = 0
        try {
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        when (
                            processEntry(
                                app,
                                entry.name,
                                entry.size,
                                entry.time,
                                openStream = { zis },
                                sharedZipInputStream = true,
                            )
                        ) {
                            Outcome.EXTRACTED -> extracted++
                            Outcome.SKIPPED -> skipped++
                            Outcome.FAILED -> failed++
                            Outcome.IGNORED -> {}
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (t: Throwable) {
            FLog.w(TAG, "Cache import interrupted", t)
        }
        return Result(extracted, skipped, failed)
    }

    @JvmStatic
    fun targetFileForEntry(context: Context, zipEntryName: String): File? {
        val normalized = normalizeEntryName(zipEntryName)
        if (!normalized.startsWith(CacheArchiveExporter.ZIP_CACHE_PREFIX)) {
            return null
        }
        return when {
            normalized.startsWith(CacheArchiveExporter.ZIP_THUMBNAILS_PREFIX) -> {
                val relative = normalized.removePrefix(CacheArchiveExporter.ZIP_THUMBNAILS_PREFIX)
                if (!isSafeRelativePath(relative)) {
                    return null
                }
                // Journal is rebuilt after import from *.0 value files; do not restore a stale one.
                if (isGlideJournalSidecar(relative)) {
                    return null
                }
                CanonicalCachePathResolver.thumbnailsDirOrNull(context)?.let { dir ->
                    File(dir, relative)
                }
            }
            normalized.startsWith(CacheArchiveExporter.ZIP_MEDIA_CACHE_PREFIX) -> {
                val relative = normalized.removePrefix(CacheArchiveExporter.ZIP_MEDIA_CACHE_PREFIX)
                if (!isSafeRelativePath(relative)) {
                    return null
                }
                CanonicalCachePathResolver.mediaCacheDirOrNull(context)?.let { dir ->
                    File(dir, relative)
                }
            }
            else -> null
        }
    }

    /**
     * Skip when a usable local blob already exists and should not be replaced by the zip entry.
     *
     * [ZipInputStream] often reports [entrySize] as -1 (data descriptor). In that case, any
     * non-empty local file that is at least as new as the zip entry is preserved.
     */
    @JvmStatic
    fun shouldSkipExtraction(targetFile: File, entrySize: Long, entryTime: Long): Boolean {
        if (!targetFile.isFile || targetFile.length() <= 0L) {
            return false
        }
        if (entrySize >= 0L && targetFile.length() != entrySize) {
            return false
        }
        if (entryTime > 0L && targetFile.lastModified() < entryTime) {
            return false
        }
        return true
    }

    private fun processEntry(
        context: Context,
        entryName: String,
        entrySize: Long,
        entryTime: Long,
        openStream: () -> InputStream,
        /** True for ZipInputStream (shared); false for ZipFile entry streams (close after use). */
        sharedZipInputStream: Boolean,
    ): Outcome {
        val target = targetFileForEntry(context, entryName) ?: run {
            if (sharedZipInputStream) {
                drainEntry(openStream())
            }
            return Outcome.IGNORED
        }
        if (shouldSkipExtraction(target, entrySize, entryTime)) {
            if (sharedZipInputStream) {
                drainEntry(openStream())
            }
            return Outcome.SKIPPED
        }
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                FLog.w(TAG, "Failed to create cache parent dir: %s", parent.absolutePath)
                if (sharedZipInputStream) {
                    drainEntry(openStream())
                }
                return Outcome.FAILED
            }
            val input = openStream()
            try {
                FileOutputStream(target).use { fos ->
                    BufferedOutputStream(fos).use { out ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            if (read > 0) {
                                out.write(buffer, 0, read)
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            } finally {
                if (!sharedZipInputStream) {
                    try {
                        input.close()
                    } catch (_: IOException) {
                    }
                }
            }
            if (entryTime > 0L) {
                target.setLastModified(entryTime)
            }
            Outcome.EXTRACTED
        } catch (t: IOException) {
            FLog.w(TAG, "Failed to extract cache entry %s", entryName, t)
            Outcome.FAILED
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to extract cache entry %s", entryName, t)
            Outcome.FAILED
        }
    }

    private fun drainEntry(input: InputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (input.read(buffer) >= 0) {
            // consume remainder of zip entry
        }
    }

    private fun openUriInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (t: NullPointerException) {
            FLog.w(TAG, "Could not open import uri", t)
            null
        } catch (t: IOException) {
            FLog.w(TAG, "Could not open import uri", t)
            null
        }
    }

    private fun normalizeEntryName(name: String): String =
        name.replace('\\', '/').trimStart('/')

    @JvmStatic
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty()) {
            return false
        }
        return path.split('/').none { segment -> segment.isEmpty() || segment == ".." }
    }

    private fun isGlideJournalSidecar(relativePath: String): Boolean {
        return relativePath == "journal" ||
            relativePath == "journal.tmp" ||
            relativePath == "journal.bkp"
    }
}
