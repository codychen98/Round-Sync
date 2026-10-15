package ca.pkay.rcloneexplorer.util

import android.content.Context
import ca.pkay.rcloneexplorer.Glide.PinnedVideoThumbnailStore
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Appends durable thumbnail assets to a config export zip:
 * - Glide DiskLruCache under `cache/thumbnails/` (on-disk: `{filesDir}/thumbnails`)
 * - Pinned video posters under `files/pinned_video_thumbnails/`
 *
 * Streamed media cache is intentionally excluded.
 */
object CacheArchiveExporter {

    private const val TAG = "CacheArchiveExporter"
    private const val COPY_BUFFER_BYTES = 8192

    const val ZIP_CACHE_PREFIX = "cache/"
    const val ZIP_THUMBNAILS_PREFIX = "${ZIP_CACHE_PREFIX}thumbnails/"
    const val ZIP_MEDIA_CACHE_PREFIX = "${ZIP_CACHE_PREFIX}media_cache/"
    const val ZIP_FILES_PREFIX = "files/"
    const val ZIP_PINNED_THUMBNAILS_PREFIX = "${ZIP_FILES_PREFIX}${PinnedVideoThumbnailStore.DIR_NAME}/"

    @JvmStatic
    fun hasCacheEntriesToExport(context: Context): Boolean {
        val app = context.applicationContext
        val thumbnailsDir = CanonicalCachePathResolver.thumbnailsDirOrNull(app)
        if (thumbnailsDir != null && isNonEmptyDirectory(thumbnailsDir)) {
            return true
        }
        return isNonEmptyDirectory(PinnedVideoThumbnailStore.directory(app))
    }

    @JvmStatic
    fun appendCacheEntries(context: Context, zos: ZipOutputStream) {
        val app = context.applicationContext
        val exported = HashSet<String>()
        val thumbnailsDir = CanonicalCachePathResolver.thumbnailsDirOrNull(app)
        if (thumbnailsDir != null && isNonEmptyDirectory(thumbnailsDir)) {
            exportDirectory(thumbnailsDir, ZIP_THUMBNAILS_PREFIX, zos, exported)
        }
        val pinnedDir = PinnedVideoThumbnailStore.directory(app)
        if (isNonEmptyDirectory(pinnedDir)) {
            exportDirectory(pinnedDir, ZIP_PINNED_THUMBNAILS_PREFIX, zos, exported)
        }
    }

    @JvmStatic
    fun zipEntryName(zipPrefix: String, relativePath: String): String =
        zipPrefix + normalizeZipRelativePath(relativePath)

    @JvmStatic
    fun relativePathWithinRoot(rootDir: File, file: File): String {
        val rootPath = rootDir.absolutePath.trimEnd(File.separatorChar)
        val filePath = file.absolutePath
        require(filePath.startsWith(rootPath)) {
            "File $filePath is not under root $rootPath"
        }
        val suffix = filePath.substring(rootPath.length).trimStart(File.separatorChar)
        return normalizeZipRelativePath(suffix)
    }

    @JvmStatic
    fun isNonEmptyDirectory(dir: File): Boolean {
        if (!dir.isDirectory) {
            return false
        }
        return dir.walkTopDown().any { it.isFile }
    }

    private fun exportDirectory(
        sourceRoot: File,
        zipPrefix: String,
        zos: ZipOutputStream,
        exported: MutableSet<String>,
    ) {
        sourceRoot.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val entryName = zipEntryName(zipPrefix, relativePathWithinRoot(sourceRoot, file))
                if (exported.add(entryName)) {
                    writeZipEntry(zos, file, entryName)
                }
            }
    }

    private fun writeZipEntry(zos: ZipOutputStream, file: File, entryName: String) {
        try {
            val entry = ZipEntry(entryName)
            val length = file.length()
            if (length >= 0L) {
                entry.size = length
            }
            val modified = file.lastModified()
            if (modified > 0L) {
                entry.time = modified
            }
            zos.putNextEntry(entry)
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var read = input.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        zos.write(buffer, 0, read)
                    }
                    read = input.read(buffer)
                }
            }
            zos.closeEntry()
        } catch (t: IOException) {
            FLog.w(TAG, "Failed to export cache entry %s", entryName, t)
            closeEntryQuietly(zos)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to export cache entry %s", entryName, t)
            closeEntryQuietly(zos)
        }
    }

    private fun closeEntryQuietly(zos: ZipOutputStream) {
        runCatching { zos.closeEntry() }
    }

    private fun normalizeZipRelativePath(path: String): String =
        path.replace('\\', '/').trimStart('/')
}
