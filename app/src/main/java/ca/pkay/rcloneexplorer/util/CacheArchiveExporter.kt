package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Appends thumbnail and media-cache blobs to a config export zip under `cache/`.
 * Legacy [SelectedFolderMediaCacheLayout] data is included when non-empty and mapped into
 * `cache/media_cache/` without duplicating paths already exported from the canonical layout.
 */
object CacheArchiveExporter {

    private const val TAG = "CacheArchiveExporter"
    private const val COPY_BUFFER_BYTES = 8192

    const val ZIP_CACHE_PREFIX = "cache/"
    const val ZIP_THUMBNAILS_PREFIX = "${ZIP_CACHE_PREFIX}thumbnails/"
    const val ZIP_MEDIA_CACHE_PREFIX = "${ZIP_CACHE_PREFIX}media_cache/"

    @JvmStatic
    fun hasCacheEntriesToExport(context: Context): Boolean {
        val app = context.applicationContext
        val candidates = listOfNotNull(
            CanonicalCachePathResolver.thumbnailsDirOrNull(app),
            CanonicalCachePathResolver.mediaCacheDirOrNull(app),
            SelectedFolderMediaCacheLayout.baseDir(app),
        )
        return candidates.any { isNonEmptyDirectory(it) }
    }

    @JvmStatic
    fun appendCacheEntries(context: Context, zos: ZipOutputStream) {
        val exported = HashSet<String>()
        val app = context.applicationContext

        CanonicalCachePathResolver.thumbnailsDirOrNull(app)?.let { dir ->
            if (isNonEmptyDirectory(dir)) {
                exportDirectory(dir, ZIP_THUMBNAILS_PREFIX, zos, exported)
            }
        }

        CanonicalCachePathResolver.mediaCacheDirOrNull(app)?.let { dir ->
            if (isNonEmptyDirectory(dir)) {
                exportDirectory(dir, ZIP_MEDIA_CACHE_PREFIX, zos, exported)
            }
        }

        val legacyBase = SelectedFolderMediaCacheLayout.baseDir(app)
        if (isNonEmptyDirectory(legacyBase)) {
            exportLegacyMediaCache(legacyBase, zos, exported)
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

    private fun exportLegacyMediaCache(
        legacyBase: File,
        zos: ZipOutputStream,
        exported: MutableSet<String>,
    ) {
        legacyBase.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val entryName = zipEntryName(
                    ZIP_MEDIA_CACHE_PREFIX,
                    relativePathWithinRoot(legacyBase, file),
                )
                if (exported.add(entryName)) {
                    writeZipEntry(zos, file, entryName)
                }
            }
    }

    private fun writeZipEntry(zos: ZipOutputStream, file: File, entryName: String) {
        try {
            zos.putNextEntry(ZipEntry(entryName))
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
