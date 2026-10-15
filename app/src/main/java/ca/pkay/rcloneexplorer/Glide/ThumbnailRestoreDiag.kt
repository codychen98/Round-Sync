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
 * - blobs never present in the backup (all alternate labels miss on disk)
 */
object ThumbnailRestoreDiag {

    private const val LOG_TITLE = "ThumbRestoreDbg"
    private const val SAMPLE_LIMIT = 24
    private const val MISS_LOG_LIMIT = 6
    /** Cap full-folder miss tallies so huge listings stay cheap. */
    private const val MISS_TALLY_LIMIT = 256

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
     * For prefetch misses (total or partial), log a few per-item probes plus folder-wide tallies
     * of current vs legacy alternate labels.
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
        val detailLimit = misses.size.coerceAtMost(MISS_LOG_LIMIT)
        val tallyLimit = misses.size.coerceAtMost(MISS_TALLY_LIMIT)
        SyncLog.info(
            app,
            LOG_TITLE,
            "event=prefetchMissSampleStart path=$directoryPath missCount=${misses.size} " +
                "detailLog=$detailLimit tallyScan=$tallyLimit",
        )

        var currentHit = 0
        var currentFile = 0
        var altDecodedV2Hit = 0
        var altEncodedNoV2Hit = 0
        var altDecodedNoV2Hit = 0
        var altImgDecodedHit = 0
        var anyAltHit = 0
        var noBlobAtAll = 0
        var lookupIdentityLikely = 0

        for (i in 0 until tallyLimit) {
            val item = misses[i]
            val remote = item.remote?.name ?: continue
            val path = item.path ?: continue
            val mime = item.mimeType
            val currentLabel = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
                cache,
                remote,
                path,
                mime,
            )
            val currentProbe = probeLabel(cache, currentLabel)
            if (currentProbe.journalHit) {
                currentHit++
            }
            if (currentProbe.safeKeyFileExists) {
                currentFile++
            }

            val alts = alternateLabels(remote, path, mime)
            val altProbes = alts.map { (name, label) -> name to probeLabel(cache, label) }
            var thisAnyAltHit = false
            var thisAnyFile = currentProbe.safeKeyFileExists
            for ((name, probe) in altProbes) {
                if (probe.safeKeyFileExists) {
                    thisAnyFile = true
                }
                if (probe.journalHit) {
                    thisAnyAltHit = true
                    when (name) {
                        "vidDecodedV2" -> altDecodedV2Hit++
                        "vidEncodedNoV2" -> altEncodedNoV2Hit++
                        "vidDecodedNoV2" -> altDecodedNoV2Hit++
                        "imgDecoded" -> altImgDecodedHit++
                    }
                }
            }
            if (thisAnyAltHit) {
                anyAltHit++
            }
            if (!thisAnyFile) {
                noBlobAtAll++
            }
            if (!currentProbe.journalHit && thisAnyAltHit) {
                lookupIdentityLikely++
            }

            if (i < detailLimit) {
                val altHits = altProbes.joinToString(" ") { (name, p) ->
                    val label = alts.first { it.first == name }.second
                    "$name:hit=${p.journalHit}:file=${p.safeKeyFileExists}:label=${label.take(48)}"
                }
                SyncLog.info(
                    app,
                    LOG_TITLE,
                    "event=prefetchMissSample i=$i mime=${mime ?: "null"} remote=$remote path=$path " +
                        "currentHit=${currentProbe.journalHit} currentFile=${currentProbe.safeKeyFileExists} " +
                        "currentSafe=${currentProbe.safeKey ?: "none"} " +
                        "currentLabel=${currentLabel?.take(64) ?: "null"} $altHits",
                )
            }
        }

        SyncLog.info(
            app,
            LOG_TITLE,
            "event=prefetchMissTally path=$directoryPath missCount=${misses.size} scanned=$tallyLimit " +
                "currentHit=$currentHit currentFile=$currentFile " +
                "altDecodedV2Hit=$altDecodedV2Hit altEncodedNoV2Hit=$altEncodedNoV2Hit " +
                "altDecodedNoV2Hit=$altDecodedNoV2Hit altImgDecodedHit=$altImgDecodedHit " +
                "anyAltHit=$anyAltHit lookupIdentityLikely=$lookupIdentityLikely " +
                "noBlobAtAll=$noBlobAtAll " +
                "identityLikelyDominant=${lookupIdentityLikely * 2 >= tallyLimit && lookupIdentityLikely > 0} " +
                "absentLikelyDominant=${noBlobAtAll * 2 >= tallyLimit && noBlobAtAll > 0}",
        )
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
