package ca.pkay.rcloneexplorer.util

import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Decides whether a cold-start enqueue of [ca.pkay.rcloneexplorer.workmanager.MediaFolderPolicyThumbnailPrefetchWorker]
 * is needed. After a run that stored nothing new, the library is "settled" for [SETTLE_TTL_MS]
 * unless the policy folder list changes (hash mismatch) or an import clears the prefs.
 */
object MediaPolicyPrefetchSettled {

    const val PREF_SETTLED_AT = "media_policy_prefetch_settled_at"
    const val PREF_SETTLED_HASH = "media_policy_prefetch_settled_hash"
    const val SETTLE_TTL_MS = 24L * 60L * 60L * 1000L

    @JvmStatic
    fun shouldEnqueueOnStart(
        settledAt: Long,
        now: Long,
        policyHash: String,
        storedHash: String,
        ttlMs: Long = SETTLE_TTL_MS,
    ): Boolean {
        if (settledAt <= 0L || storedHash.isEmpty()) {
            return true
        }
        if (policyHash != storedHash) {
            return true
        }
        return now - settledAt >= ttlMs
    }

    /**
     * True when this folder run did not store a new thumbnail: every probe was a hit, or every
     * miss was attempted and failed. A server/lease failure (misses left unattempted) is not quiet.
     */
    @JvmStatic
    fun folderIsQuiet(outcome: ThumbnailPrefetchExecutor.FolderPrefetchOutcome): Boolean {
        if (outcome.stoppedEarly) {
            return false
        }
        val cachedCount = outcome.total - outcome.misses
        val fetchedOk = outcome.loaded - cachedCount
        if (outcome.misses <= 0) {
            return fetchedOk <= 0
        }
        val attempted = fetchedOk + outcome.failed
        return attempted == outcome.misses && fetchedOk <= 0
    }

    @JvmStatic
    fun shouldMarkSettled(
        stopped: Boolean,
        unverified: Boolean,
        folderOutcomes: List<ThumbnailPrefetchExecutor.FolderPrefetchOutcome>,
    ): Boolean {
        if (stopped || unverified) {
            return false
        }
        return folderOutcomes.all { folderIsQuiet(it) }
    }

    @JvmStatic
    fun policyHash(folders: List<PolicyPrefetchFolder>): String {
        val canonical = folders.joinToString("\n") { "${it.remoteName}\t${it.policyRelativePath}" }
        return sha256Hex(canonical)
    }

    @JvmStatic
    fun markSettled(prefs: SharedPreferences, nowMs: Long, hash: String) {
        prefs.edit()
            .putLong(PREF_SETTLED_AT, nowMs)
            .putString(PREF_SETTLED_HASH, hash)
            .apply()
    }

    @JvmStatic
    fun clear(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_SETTLED_AT)
            .remove(PREF_SETTLED_HASH)
            .apply()
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }
}
