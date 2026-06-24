package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Throttled thumbnail diagnostics for post-mortem log export.
 * Filter sync log exports by title {@code ThumbDiagDbg}.
 */
object ThumbnailDiagLog {

    const val LOG_TITLE = "ThumbDiagDbg"
    private const val COOLDOWN_MS = 5_000L
    private const val MAX_KEYS = 1_000

    private val lastLoggedAt = ConcurrentHashMap<String, Long>()

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
    fun error(context: Context?, event: String, details: String) {
        if (context == null) {
            return
        }
        SyncLog.error(context.applicationContext, LOG_TITLE, "event=$event $details")
    }
}
