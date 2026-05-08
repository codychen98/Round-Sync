package ca.pkay.rcloneexplorer.util

import androidx.media3.datasource.cache.Cache
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Writes pre-fetched HEAD/TAIL byte ranges from a sparse local file into a Media3 [Cache].
 *
 * Used by [ca.pkay.rcloneexplorer.Glide.SparseLocalThumbnailExtractor] to persist B2 bytes into
 * [SelectedFolderSimpleCacheProvider]'s SimpleCache so that subsequent video playback for
 * cache-policy folders can read the head of the file from disk without re-downloading.
 *
 * The stable cache key from [RemotePathCacheKeyFactory] must be used when calling
 * [commitRanges]; see [SelectedFolderSimpleCacheProvider.keyFactory].
 */
object SimpleCacheRangeWriter {

    /**
     * Minimal write contract used internally and in pure-JVM tests to decouple range-copy logic
     * from Media3's [Cache]/[CacheSpan] types.
     *
     * @return true if the range was committed (or was already present); false on any error.
     */
    internal fun interface RangeWriter {
        fun write(offset: Long, bytes: ByteArray): Boolean
    }

    /**
     * Commits [ranges] from [srcFile] into [simpleCache] under [cacheKey].
     *
     * For each range: acquires a write span via [Cache.startReadWriteNonBlocking] (returns false
     * immediately if the span is locked by another writer rather than blocking), copies bytes from
     * [srcFile] at the given offset, commits via [Cache.commitFile]. Any [Cache.CacheException]
     * for a single range is caught so remaining ranges still proceed.
     *
     * @return true if at least one range was committed (or already present) successfully.
     */
    @JvmStatic
    fun commitRanges(
        simpleCache: Cache,
        cacheKey: String,
        ranges: List<LongRange>,
        srcFile: RandomAccessFile,
    ): Boolean {
        val writer = RangeWriter { offset, bytes ->
            commitSingleRange(simpleCache, cacheKey, offset, bytes)
        }
        return commitRangesInternal(writer, ranges, srcFile)
    }

    /**
     * Internal entry point usable in pure-JVM tests via a [RangeWriter] fake.
     */
    internal fun commitRangesInternal(
        writer: RangeWriter,
        ranges: List<LongRange>,
        srcFile: RandomAccessFile,
    ): Boolean {
        var anyCommitted = false
        for (range in ranges) {
            val length = range.last - range.first + 1
            if (length <= 0L || length > Int.MAX_VALUE.toLong()) continue
            try {
                val bytes = ByteArray(length.toInt())
                srcFile.seek(range.first)
                srcFile.readFully(bytes)
                if (writer.write(range.first, bytes)) {
                    anyCommitted = true
                }
            } catch (_: Exception) {
                // Skip this range; subsequent ranges still proceed.
            }
        }
        return anyCommitted
    }

    private fun commitSingleRange(
        simpleCache: Cache,
        cacheKey: String,
        offset: Long,
        bytes: ByteArray,
    ): Boolean {
        val length = bytes.size.toLong()
        val span = try {
            simpleCache.startReadWriteNonBlocking(cacheKey, offset, length)
        } catch (_: Cache.CacheException) {
            return false
        } ?: return false  // span locked by concurrent writer; skip

        return if (span.isCached) {
            true  // data already present; nothing to write
        } else {
            val targetFile = span.file ?: run {
                runCatching { simpleCache.releaseHoleSpan(span) }
                return false
            }
            try {
                FileOutputStream(targetFile).use { out -> out.write(bytes) }
                simpleCache.commitFile(targetFile, length)
                true
            } catch (_: Exception) {
                runCatching { simpleCache.releaseHoleSpan(span) }
                false
            }
        }
    }
}
