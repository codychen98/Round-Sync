package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.util.MediaFolderPolicy
import ca.pkay.rcloneexplorer.util.PolicyType
import ca.pkay.rcloneexplorer.util.SelectedFolderSimpleCacheProvider
import ca.pkay.rcloneexplorer.util.SimpleCacheRangeWriter
import ca.pkay.rcloneexplorer.util.SyncLog
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/**
 * B2 (third-pass) thumbnail extraction strategy.
 *
 * Downloads only the HEAD and TAIL byte ranges of the remote file into a sparse
 * local file, then runs MediaMetadataRetriever on the local path.
 *
 * This handles non-progressive containers (e.g. MKV with cues at end-of-file) that
 * defeat MMR-over-HTTP and ExoPlayer within their prepare budgets, because the
 * container header and early video keyframes are reachable within HEAD_BYTES.
 *
 * At most one B2 extraction runs at a time (Semaphore(1)); callers that lose the
 * race return null immediately rather than queuing, since B2 is a last resort and
 * blocking many threads would stall the UI with grey tiles.
 *
 * Use [buildHandoffSink] to obtain a [SparseHandoffSink] that persists HEAD/TAIL
 * bytes into [SelectedFolderSimpleCacheProvider]'s SimpleCache for cache-policy
 * folders so that subsequent playback skips re-downloading the head of the file.
 */
internal object SparseLocalThumbnailExtractor {

    const val HEAD_BYTES: Long = 24L * 1024L * 1024L
    const val TAIL_BYTES: Long = 8L * 1024L * 1024L
    /** Minimum file size: HEAD + TAIL + 1 MiB gap, so ranges never overlap. */
    const val MIN_FILE_BYTES: Long = HEAD_BYTES + TAIL_BYTES + (1L * 1024L * 1024L)
    /** Hard ceiling — skip files unlikely to fit in device storage. */
    const val MAX_FILE_BYTES: Long = 32L * 1024L * 1024L * 1024L

    private val semaphore = Semaphore(1)

    /**
     * Called after MMR finishes (success OR failure). When it returns true the
     * extractor must NOT delete the source file — the sink owns the bytes.
     * Implemented in Step 7.
     */
    fun interface SparseHandoffSink {
        fun maybeHandoff(srcFile: File, fileSizeBytes: Long, ranges: List<LongRange>): Boolean
    }

    @JvmStatic
    fun tryExtract(
        appContext: Context,
        url: String,
        fileSizeBytes: Long,
        owner: VideoThumbnailFetcher,
        handoffSink: SparseHandoffSink? = null,
    ): Bitmap? {
        if (fileSizeBytes < MIN_FILE_BYTES || fileSizeBytes > MAX_FILE_BYTES) {
            log(appContext, "sparseSkip", "reason=sizeOob fileSizeBytes=$fileSizeBytes basename=${url.substringAfterLast('/')}")
            return null
        }
        if (!semaphore.tryAcquire()) {
            log(appContext, "sparseSkip", "reason=semaphoreBusy basename=${url.substringAfterLast('/')}")
            return null
        }
        return try {
            doExtract(appContext, url, fileSizeBytes, owner, handoffSink)
        } finally {
            semaphore.release()
        }
    }

    private fun doExtract(
        appContext: Context,
        url: String,
        fileSizeBytes: Long,
        owner: VideoThumbnailFetcher,
        handoffSink: SparseHandoffSink?,
    ): Bitmap? {
        val basename = url.substringAfterLast('/')
        log(appContext, "sparseStart", "fileSizeBytes=$fileSizeBytes basename=$basename")

        val sparseDir = File(appContext.cacheDir, "sparse_thumb").also { it.mkdirs() }
        val tmpFile = File(sparseDir, sha1Hex(url) + ".tmp")

        val headRange = 0L until HEAD_BYTES
        val tailRange = (fileSizeBytes - TAIL_BYTES) until fileSizeBytes
        val ranges = listOf(headRange, tailRange)

        var handoffTookFile = false
        try {
            RandomAccessFile(tmpFile, "rw").use { raf ->
                raf.setLength(fileSizeBytes)
                for (range in ranges) {
                    if (owner.isCancelled) {
                        log(appContext, "sparseCancel", "phase=rangeFetch basename=$basename")
                        return null
                    }
                    val bytes = fetchRange(appContext, url, range.first, range.last - range.first + 1)
                        ?: run {
                            log(appContext, "sparseRangeFail", "rangeStart=${range.first} basename=$basename")
                            return null
                        }
                    raf.seek(range.first)
                    raf.write(bytes)
                    log(appContext, "sparseRangeOk", "rangeStart=${range.first} bytes=${bytes.size} basename=$basename")
                }
            }

            if (owner.isCancelled) {
                log(appContext, "sparseCancel", "phase=beforeMmr basename=$basename")
                return null
            }

            val bitmap = runMmrOnLocalFile(tmpFile.absolutePath, owner)
            log(appContext, "mmrLocalEnd", "result=${if (bitmap != null) "frame" else "noFrame"} basename=$basename")

            // Handoff runs after MMR regardless of bitmap success so that HEAD/TAIL bytes
            // already on disk are committed to SimpleCache for future video playback even
            // when MMR cannot extract a thumbnail from the sparse file.
            if (handoffSink != null && !owner.isCancelled) {
                handoffTookFile = runCatching {
                    handoffSink.maybeHandoff(tmpFile, fileSizeBytes, ranges)
                }.getOrDefault(false)
            }
            return bitmap
        } finally {
            if (!handoffTookFile) {
                tmpFile.delete()
            }
        }
    }

    /**
     * Returns a [SparseHandoffSink] that, after B2 extraction, persists HEAD/TAIL bytes into
     * [SelectedFolderSimpleCacheProvider]'s SimpleCache when [url]'s folder is cache-policy ON.
     *
     * Policy check: reads the CACHE allow-list for the remote; if the file's path is not covered,
     * logs `phase=handoffSkip reason=policyOff` and returns false (B2 deletes the sparse file).
     * If the cache is disabled or the policy prefs are unreadable, returns false likewise.
     */
    @JvmStatic
    fun buildHandoffSink(appContext: Context, url: String): SparseHandoffSink {
        return SparseHandoffSink { srcFile, _, ranges ->
            val stablePath = VideoThumbnailUrl.stablePathFor(url)
            val noLeadSlash = stablePath.trimStart('/')
            val slashIdx = noLeadSlash.indexOf('/')
            if (slashIdx < 0) {
                log(appContext, "handoffSkip", "reason=parseError basename=${url.substringAfterLast('/')}")
                return@SparseHandoffSink false
            }
            val remoteName = noLeadSlash.substring(0, slashIdx)
            val remotePath = noLeadSlash.substring(slashIdx + 1)

            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            val allowedFolders = MediaFolderPolicy.readAllowedFolders(prefs, remoteName, PolicyType.CACHE)
            if (allowedFolders.isEmpty() || !MediaFolderPolicy.matchesFolder(allowedFolders, remotePath)) {
                log(appContext, "handoffSkip", "reason=policyOff basename=${url.substringAfterLast('/')}")
                return@SparseHandoffSink false
            }

            val simpleCache = SelectedFolderSimpleCacheProvider.getOrNull(appContext)
            if (simpleCache == null) {
                log(appContext, "handoffSkip", "reason=cacheNull basename=${url.substringAfterLast('/')}")
                return@SparseHandoffSink false
            }

            val cacheKey = SelectedFolderSimpleCacheProvider.keyFactory()
                .buildCacheKey(DataSpec(Uri.parse(url)))

            val raf = try { RandomAccessFile(srcFile, "r") } catch (_: Exception) {
                return@SparseHandoffSink false
            }
            try {
                val committed = SimpleCacheRangeWriter.commitRanges(simpleCache, cacheKey, ranges, raf)
                log(appContext, if (committed) "handoffOk" else "handoffFail",
                    "basename=${url.substringAfterLast('/')} cacheKey=$cacheKey")
                committed
            } catch (_: Exception) {
                log(appContext, "handoffFail", "basename=${url.substringAfterLast('/')}")
                false
            } finally {
                runCatching { raf.close() }
            }
        }
    }

    private fun fetchRange(appContext: Context, url: String, offset: Long, length: Long): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        return try {
            OkHttpMediaDataSource.getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (e: Exception) {
            log(appContext, "sparseRangeFail", "offset=$offset ex=${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Probes early timestamps only, since only HEAD_BYTES worth of video data is
     * present in the sparse file. The container header in the first bytes is
     * sufficient for MMR to parse metadata and decode early keyframes.
     */
    private fun runMmrOnLocalFile(path: String, owner: VideoThumbnailFetcher): Bitmap? {
        val earlyTimesUs = longArrayOf(0L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L)
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            earlyTimesUs.asSequence()
                .takeWhile { !owner.isCancelled }
                .mapNotNull { ts ->
                    try { mmr.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                    catch (_: Exception) { null }
                }
                .firstOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun log(appContext: Context, phase: String, attrs: String) {
        SyncLog.info(appContext.applicationContext, "VidThumbDbg", "event=thumbPipe phase=$phase $attrs")
    }
}
