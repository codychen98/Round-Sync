package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Restores thumbnail and media-cache blobs from a config export zip `cache/` tree into
 * [CanonicalCachePathResolver] locations. Missing `cache/` entries are ignored (older exports).
 */
object CacheArchiveImporter {

    private const val TAG = "CacheArchiveImporter"
    private const val COPY_BUFFER_BYTES = 8192

    data class Result(
        val extracted: Int,
        val skipped: Int,
        val failed: Int,
    )

    private enum class Outcome {
        EXTRACTED,
        SKIPPED,
        FAILED,
        IGNORED,
    }

    @JvmStatic
    fun extractFromZip(context: Context, uri: Uri): Result {
        val inputStream = openUriInputStream(context.applicationContext, uri)
            ?: return Result(0, 0, 0)
        return extractFromZipStream(context, inputStream)
    }

    @JvmStatic
    fun extractFromZipFile(context: Context, file: File): Result {
        return try {
            file.inputStream().use { inputStream ->
                extractFromZipStream(context, inputStream)
            }
        } catch (t: IOException) {
            FLog.w(TAG, "Could not open import file", t)
            Result(0, 0, 0)
        }
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
                        when (processEntry(app, zis, entry)) {
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

    @JvmStatic
    fun shouldSkipExtraction(targetFile: File, entrySize: Long, entryTime: Long): Boolean {
        if (entrySize < 0L) {
            return false
        }
        if (!targetFile.isFile) {
            return false
        }
        if (targetFile.length() != entrySize) {
            return false
        }
        return targetFile.lastModified() >= entryTime
    }

    private fun processEntry(context: Context, zis: ZipInputStream, entry: ZipEntry): Outcome {
        val target = targetFileForEntry(context, entry.name) ?: run {
            drainEntry(zis)
            return Outcome.IGNORED
        }
        if (shouldSkipExtraction(target, entry.size, entry.time)) {
            drainEntry(zis)
            return Outcome.SKIPPED
        }
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                FLog.w(TAG, "Failed to create cache parent dir: %s", parent.absolutePath)
                drainEntry(zis)
                return Outcome.FAILED
            }
            FileOutputStream(target).use { fos ->
                BufferedOutputStream(fos).use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var read = zis.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            out.write(buffer, 0, read)
                        }
                        read = zis.read(buffer)
                    }
                }
            }
            if (entry.time > 0L) {
                target.setLastModified(entry.time)
            }
            Outcome.EXTRACTED
        } catch (t: IOException) {
            FLog.w(TAG, "Failed to extract cache entry %s", entry.name, t)
            Outcome.FAILED
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to extract cache entry %s", entry.name, t)
            Outcome.FAILED
        }
    }

    private fun drainEntry(zis: ZipInputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (zis.read(buffer) >= 0) {
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
}
