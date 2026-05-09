package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
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

    /** Seam for resolving the current thumbnail server base URL; injectable for unit tests. */
    internal fun interface BaseUrlSupplier {
        fun getCurrentBaseUrlOrNull(): String?
    }

    /** Seam for waiting until the thumbnail server is READY; injectable for unit tests. */
    internal fun interface ServerReadyAwaiter {
        fun awaitReady(timeoutMs: Long): Boolean
    }

    /** Seam for executing a byte-range HTTP request; injectable for unit tests. */
    internal fun interface RangeRequester {
        fun execute(url: String, offset: Long, length: Long): ByteArray?
    }

    /** Seam for emitting diagnostic log lines; injectable for unit tests. */
    internal fun interface FetchLogger {
        fun log(phase: String, attrs: String)
    }

    /** Internal result type for [fetchRange] so callers can distinguish failure modes. */
    internal sealed class FetchRangeResult {
        data class Success(val bytes: ByteArray) : FetchRangeResult()
        object NetworkFailed : FetchRangeResult()
        object ManagerNotReady : FetchRangeResult()
    }

    /**
     * Classification of the B2 extraction attempt. Used by [VideoThumbnailFetcher] to gate
     * blacklist writes: only [BYTES_OK_NO_FRAME] (ranges downloaded, MMR found no frame) is a
     * genuine signal that the container is unsupported. Infrastructure failures
     * ([NETWORK_FAILED], [MANAGER_NOT_READY]) must not permanently suppress the file.
     */
    enum class B2Result {
        /** Bitmap extracted from the local sparse file. */
        OK,
        /** HEAD + TAIL downloaded successfully but MMR returned no frame. */
        BYTES_OK_NO_FRAME,
        /** ConnectException / 4xx / 5xx after retry-once exhausted. */
        NETWORK_FAILED,
        /** ThumbnailServerManager not READY within the awaitReady budget. */
        MANAGER_NOT_READY,
        /** Another B2 extraction was already in flight; we returned immediately. */
        SEMAPHORE_BUSY,
        /** File size outside [MIN_FILE_BYTES, MAX_FILE_BYTES]. */
        SIZE_OOB,
        /** owner.isCancelled became true mid-flight. */
        CANCELLED,
    }

    /** Carries the [B2Result] classification and the extracted [bitmap] (non-null only for [B2Result.OK]). */
    data class B2Outcome(val result: B2Result, val bitmap: Bitmap?)

    private val defaultBaseUrlSupplier = BaseUrlSupplier {
        ThumbnailServerManager.getInstance().getCurrentBaseUrlOrNull()
    }

    private val defaultServerReadyAwaiter = ServerReadyAwaiter { timeoutMs ->
        ThumbnailServerManager.getInstance().awaitReady(timeoutMs)
    }

    private val defaultRangeRequester = RangeRequester { url, offset, length ->
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        try {
            OkHttpMediaDataSource.getClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun tryExtract(
        appContext: Context,
        url: String,
        fileSizeBytes: Long,
        owner: VideoThumbnailFetcher,
        handoffSink: SparseHandoffSink? = null,
    ): B2Outcome {
        if (fileSizeBytes < MIN_FILE_BYTES || fileSizeBytes > MAX_FILE_BYTES) {
            log(appContext, "sparseSkip", "reason=sizeOob fileSizeBytes=$fileSizeBytes basename=${url.substringAfterLast('/')}")
            return B2Outcome(B2Result.SIZE_OOB, null)
        }
        if (!semaphore.tryAcquire()) {
            log(appContext, "sparseSkip", "reason=semaphoreBusy basename=${url.substringAfterLast('/')}")
            return B2Outcome(B2Result.SEMAPHORE_BUSY, null)
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
    ): B2Outcome {
        val basename = url.substringAfterLast('/')
        val stablePath = VideoThumbnailUrl.stablePathFor(url)

        val pinnedPort = parsePinnedUrlPort(url)
        val pinnedAuth6 = parsePinnedUrlAuth6(url)
        val mgr = ThumbnailServerManager.getInstance()
        val mgrBase = mgr.getCurrentBaseUrlOrNull()
        val mgrPort = if (mgrBase != null) parsePinnedUrlPort(mgrBase) else -1
        val mgrAuth6 = if (mgrBase != null) parsePinnedUrlAuth6(mgrBase) else "?"
        val mgrState = mgr.getSyncState()
        val stale = mgrBase != null && (pinnedPort != mgrPort || pinnedAuth6 != mgrAuth6)
        log(appContext, "sparseStart",
            "fileSizeBytes=$fileSizeBytes basename=$basename" +
                " urlPort=$pinnedPort urlAuth6=$pinnedAuth6 mgrPort=$mgrPort mgrAuth6=$mgrAuth6" +
                " mgrState=$mgrState stale=$stale")

        val sparseDir = File(appContext.cacheDir, "sparse_thumb").also { it.mkdirs() }
        val tmpFile = File(sparseDir, sha1Hex(url) + ".tmp")

        val headRange = 0L until HEAD_BYTES
        val tailRange = (fileSizeBytes - TAIL_BYTES) until fileSizeBytes
        val ranges = listOf(headRange, tailRange)

        val fetchLogger = FetchLogger { phase, attrs -> log(appContext, phase, attrs) }
        var handoffTookFile = false
        try {
            RandomAccessFile(tmpFile, "rw").use { raf ->
                raf.setLength(fileSizeBytes)
                for (range in ranges) {
                    if (owner.isCancelled) {
                        log(appContext, "sparseCancel", "phase=rangeFetch basename=$basename")
                        return B2Outcome(B2Result.CANCELLED, null)
                    }
                    val fetchResult = fetchRange(
                        stablePath, range.first, range.last - range.first + 1, 1,
                        defaultBaseUrlSupplier, defaultServerReadyAwaiter, defaultRangeRequester,
                        fetchLogger, basename,
                    )
                    when (fetchResult) {
                        is FetchRangeResult.Success -> {
                            raf.seek(range.first)
                            raf.write(fetchResult.bytes)
                        }
                        is FetchRangeResult.ManagerNotReady ->
                            return B2Outcome(B2Result.MANAGER_NOT_READY, null)
                        is FetchRangeResult.NetworkFailed ->
                            return B2Outcome(B2Result.NETWORK_FAILED, null)
                    }
                }
            }

            if (owner.isCancelled) {
                log(appContext, "sparseCancel", "phase=beforeMmr basename=$basename")
                return B2Outcome(B2Result.CANCELLED, null)
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
            return B2Outcome(if (bitmap != null) B2Result.OK else B2Result.BYTES_OK_NO_FRAME, bitmap)
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

    /**
     * Resolves the current server base URL, builds a Range request to [stablePath], and returns
     * [FetchRangeResult.Success] on a 2xx response. On any network failure or non-2xx, retries
     * once (attempt 2) with a freshly resolved base URL. Returns [FetchRangeResult.ManagerNotReady]
     * when the server is not READY within a 3 s budget (attempt 1 only); returns
     * [FetchRangeResult.NetworkFailed] when both attempts fail.
     *
     * Injectable seams ([urlSupplier], [readyAwaiter], [requester], [logger]) allow pure-JVM unit
     * testing without real OkHttp or ThumbnailServerManager.
     */
    internal fun fetchRange(
        stablePath: String,
        offset: Long,
        length: Long,
        attempt: Int,
        urlSupplier: BaseUrlSupplier,
        readyAwaiter: ServerReadyAwaiter,
        requester: RangeRequester,
        logger: FetchLogger,
        basename: String,
    ): FetchRangeResult {
        var baseUrl = urlSupplier.getCurrentBaseUrlOrNull()
        if (baseUrl == null) {
            if (attempt == 1) {
                readyAwaiter.awaitReady(3000)
                baseUrl = urlSupplier.getCurrentBaseUrlOrNull()
            }
            if (baseUrl == null) {
                logger.log("sparseRangeFail", "reason=mgrNotReady offset=$offset basename=$basename")
                return FetchRangeResult.ManagerNotReady
            }
        }
        val requestUrl = baseUrl + stablePath
        val resolvedPort = parsePinnedUrlPort(requestUrl)
        val resolvedAuth6 = parsePinnedUrlAuth6(requestUrl)
        logger.log("sparseRangeReq",
            "attempt=$attempt resolvedPort=$resolvedPort resolvedAuth6=$resolvedAuth6" +
                " offset=$offset basename=$basename")

        val bytes = requester.execute(requestUrl, offset, length)
        if (bytes != null) {
            logger.log("sparseRangeOk",
                "attempt=$attempt bytes=${bytes.size} resolvedPort=$resolvedPort basename=$basename")
            return FetchRangeResult.Success(bytes)
        }

        return if (attempt == 1) {
            logger.log("sparseRangeRetry", "attempt=1 offset=$offset basename=$basename")
            fetchRange(stablePath, offset, length, 2,
                urlSupplier, readyAwaiter, requester, logger, basename)
        } else {
            logger.log("sparseRangeFail", "attempt=$attempt offset=$offset basename=$basename")
            FetchRangeResult.NetworkFailed
        }
    }

    /**
     * Extracts the port number from a URL of the form {@code http://127.0.0.1:<port>/...}.
     * Returns -1 if the URL cannot be parsed.
     */
    internal fun parsePinnedUrlPort(url: String): Int {
        val colonIdx = url.indexOf("://")
        if (colonIdx < 0) return -1
        val hostPort = url.substring(colonIdx + 3).substringBefore('/')
        val portStr = hostPort.substringAfterLast(':', "")
        return portStr.toIntOrNull() ?: -1
    }

    /**
     * Extracts the first 6 characters of the auth token from a URL of the form
     * {@code http://127.0.0.1:<port>/<auth>/...}. Returns "?" if the URL cannot be parsed.
     */
    internal fun parsePinnedUrlAuth6(url: String): String {
        val colonIdx = url.indexOf("://")
        if (colonIdx < 0) return "?"
        val afterScheme = url.substring(colonIdx + 3)
        val firstSlash = afterScheme.indexOf('/')
        if (firstSlash < 0) return "?"
        val afterHost = afterScheme.substring(firstSlash + 1)
        val auth = afterHost.substringBefore('/')
        return when {
            auth.isEmpty() -> "?"
            auth.length >= 6 -> auth.substring(0, 6)
            else -> auth
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
