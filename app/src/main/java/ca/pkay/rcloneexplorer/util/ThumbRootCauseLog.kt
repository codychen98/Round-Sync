package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttled thumbnail root-cause diagnostics for post-mortem sync.log export.
 * Filter exports by title [LOG_TITLE]. Enabled on both debug and stable APKs.
 */
object ThumbRootCauseLog {

    const val LOG_TITLE = "ThumbRootCauseDbg"
    private const val COOLDOWN_MS = 5_000L
    private const val MAX_KEYS = 1_000

    private val lastLoggedAt = ConcurrentHashMap<String, Long>()

    private val clearReason = ThreadLocal<String?>()

    @JvmStatic
    fun info(context: Context?, event: String, details: String) {
        if (context == null) {
            return
        }
        val key = "$event|$details"
        val now = System.currentTimeMillis()
        val last = lastLoggedAt[key]
        if (last != null && now - last < COOLDOWN_MS) {
            return
        }
        if (lastLoggedAt.size > MAX_KEYS) {
            lastLoggedAt.clear()
        }
        lastLoggedAt[key] = now
        SyncLog.info(context.applicationContext, LOG_TITLE, "event=$event $details")
    }

    @JvmStatic
    fun beginIntentionalClear(reason: String) {
        clearReason.set(reason)
    }

    @JvmStatic
    fun endIntentionalClear() {
        clearReason.remove()
    }

    @JvmStatic
    fun currentClearReason(): String? = clearReason.get()

    /**
     * Logs a fileIcon clear. When [currentClearReason] is set, the clear is tagged;
     * otherwise it is logged as untagged (H4 probe).
     */
    @JvmStatic
    fun logIconDrawableCleared(
        context: Context?,
        rowPath: String?,
        holderHash: Int,
        extra: String,
    ) {
        val reason = clearReason.get()
        val tagged = reason != null
        info(
            context,
            "iconDrawableCleared",
            "tagged=$tagged reason=${reason ?: "untagged"} rowPath=${rowPath ?: "null"}"
                + " holderHash=$holderHash $extra",
        )
    }
}
