package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.SyncLog
import com.bumptech.glide.signature.ObjectKey
import java.io.File

/**
 * Diagnostic-only logging to distinguish post-import thumbnail miss causes:
 * - journal orphans (*.0 on disk but DiskLruCache.get misses)
 * - probe label / key-format mismatch (journal resolves samples, but current labels miss;
 *   an alternate legacy label hits)
 */
object ThumbnailRestoreDiag {

    private const val LOG_TITLE = "ThumbRestoreDbg"
    private const val SAMPLE_LIMIT = 24
    private const val MISS_LOG_LIMIT = 6

    /** Call immediately before zip extract into the thumbnails dir. */
    @JvmStatic
    fun logPreExtract(context: Context) {
        val app = context.applicationContext
        val dir = CanonicalCachePathResolver.thumbnailsDirOrNull(app)
        val holder = GlideDiskCacheHolder.peekForDiag()
        val journal = dir?.let { File(it, "journal") }
        SyncLog.info(
            app,
            LOG_TITLE,
            "event=importPreExtract holderAlive=${holder != null} " +
                "journalOpen=${holder?.isJournalOpen() == true} " +
                "holderMaxMiB=${holder?.maxSizeBytes?.let { it / (1024L * 1024L) } ?: "none"} " +
                "usageMiB=${dir?.let { ThumbnailDiskCacheSize.directoryUsageBytes(it) / (1024L * 1024L) } ?: 0} " +
                "entries=${dir?.let { ThumbnailDiskCacheSize.committedEntryCount(it) } ?: 0} " +
                "journalExists=${journal?.isFile == true} " +
                "journalBytes=${journal?.takeIf { it.isFile }?.length() ?: -1}",
        )
    }

    /**
     * After import reopen: sample committed `*.0` files and ask the live journal whether it
     * still indexes those safe keys. High sampleExist + low journalHit => orphans.
     */
    @JvmStatic
    fun logJournalSample(context: Context, cache: ThumbnailDiskCache) {
        val app = context.applicationContext
        val dir = cache.directory
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".0") }?.toList().orEmpty()
        val sample = files.take(SAMPLE_LIMIT)
        var journalHit = 0
        var sampleExist = 0
        for (file in sample) {
            if (!file.isFile || file.length() <= 0L) {
                continue
            }
            sampleExist++
            val safeKey = file.name.removeSuffix(".0")
            if (cache.fileForSafeKey(safeKey) != null) {
                journalHit++
            }
        }
        val journal = File(dir, "journal")
        val journalBak = File(dir, "journal.bak")
        val journalTmp = File(dir, "journal.tmp")
        SyncLog.info(
            app,
            LOG_TITLE,
            "event=importJournalSample sampleExist=$sampleExist journalHit=$journalHit " +
                "sampleSize=${sample.size} totalEntries=${files.size} " +
                "orphanLikely=${sampleExist > 0 && journalHit == 0} " +
                "partialOrphan=${journalHit in 1 until sampleExist} " +
                "journalBytes=${journal.takeIf { it.isFile }?.length() ?: -1} " +
                "journalBakBytes=${journalBak.takeIf { it.isFile }?.length() ?: -1} " +
                "journalTmpBytes=${journalTmp.takeIf { it.isFile }?.length() ?: -1} " +
                "journalOpen=${cache.isJournalOpen()}",
        )
    }

    /**
     * For the first few prefetch misses in a folder, log current vs alternate legacy labels and
     * whether each resolves in the journal / exists as a hashed file.
     */
    @JvmStatic
    fun logPrefetchMissSamples(
        context: Context,
        cache: ThumbnailDiskCache,
        directoryPath: String,
        misses: List<FileItem>,
    ) {
        if (misses.isEmpty()) {
            return
        }
        val app = context.applicationContext
        val limit = misses.size.coerceAtMost(MISS_LOG_LIMIT)
        SyncLog.info(
            app,
            LOG_TITLE,
            "event=prefetchMissSampleStart path=$directoryPath missCount=${misses.size} logging=$limit",
        )
        for (i in 0 until limit) {
            val item = misses[i]
            val remote = item.remote?.name ?: "null"
            val path = item.path ?: "null"
            val mime = item.mimeType ?: "null"
            val currentLabel = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
                cache,
                remote,
                path,
                item.mimeType,
            )
            val alts = alternateLabels(remote, path, item.mimeType)
            val currentProbe = probeLabel(cache, currentLabel)
            val altHits = alts.map { (name, label) ->
                val p = probeLabel(cache, label)
                "$name:hit=${p.journalHit}:file=${p.safeKeyFileExists}:label=${label.take(48)}"
            }.joinToString(" ")
            SyncLog.info(
                app,
                LOG_TITLE,
                "event=prefetchMissSample i=$i mime=$mime remote=$remote path=$path " +
                    "currentHit=${currentProbe.journalHit} currentFile=${currentProbe.safeKeyFileExists} " +
                    "currentSafe=${currentProbe.safeKey ?: "none"} " +
                    "currentLabel=${currentLabel?.take(64) ?: "null"} $altHits",
            )
        }
    }

    private data class LabelProbe(
        val safeKey: String?,
        val journalHit: Boolean,
        val safeKeyFileExists: Boolean,
    )

    private fun probeLabel(cache: ThumbnailDiskCache, label: String?): LabelProbe {
        if (label == null) {
            return LabelProbe(safeKey = null, journalHit = false, safeKeyFileExists = false)
        }
        val safeKey = cache.safeKeyFor(ObjectKey(label))
        val journalHit = cache.fileForSafeKey(safeKey) != null
        val fileExists = File(cache.directory, "$safeKey.0").isFile
        return LabelProbe(safeKey = safeKey, journalHit = journalHit, safeKeyFileExists = fileExists)
    }

    /**
     * Historical / alternate identities that may exist in backups made before encoding or
     * thumbV2 changes. Diagnostic only — not used for loading.
     */
    private fun alternateLabels(
        remoteName: String,
        remoteFilePath: String,
        mimeType: String?,
    ): List<Pair<String, String>> {
        if (mimeType == null) {
            return emptyList()
        }
        val decoded = ThumbnailCacheIdentity.stableServePath(remoteName, remoteFilePath)
        val encoded = ThumbnailCacheIdentity.legacyEncodedServePath(remoteName, remoteFilePath)
        return when {
            mimeType.startsWith("image/") -> listOf(
                "imgDecoded" to ReadableCacheKey.fromStablePath(decoded, "thumbFile"),
                "imgEncoded" to ReadableCacheKey.fromStablePath(encoded, "thumbFile"),
            )
            mimeType.startsWith("video/") -> listOf(
                "vidDecodedV2" to ReadableCacheKey.fromStablePath(decoded + "|thumbV2", "thumbVideo"),
                "vidEncodedNoV2" to ReadableCacheKey.fromStablePath(encoded, "thumbVideo"),
                "vidDecodedNoV2" to ReadableCacheKey.fromStablePath(decoded, "thumbVideo"),
            )
            else -> emptyList()
        }
    }
}
