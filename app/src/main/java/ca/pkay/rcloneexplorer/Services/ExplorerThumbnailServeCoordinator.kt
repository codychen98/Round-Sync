package ca.pkay.rcloneexplorer.Services

import android.content.Context
import android.os.Handler
import android.os.Looper
import ca.pkay.rcloneexplorer.util.SyncLog

/**
 * Bridges [FileExplorerFragment] thumbnail serve lifecycle with background callers
 * (e.g. [ca.pkay.rcloneexplorer.Glide.ThumbnailReloadHelper]) that need the HTTP
 * thumbnail server restarted after prefetch preempts the explorer lease.
 */
object ExplorerThumbnailServeCoordinator {

    private const val LOG_TAG = "ThumbnailServer"
    private val lock = Any()

    @Volatile
    private var serverDesired = false

    private var restartAction: (() -> Unit)? = null

    @JvmStatic
    fun setServerDesired(desired: Boolean) {
        serverDesired = desired
    }

    @JvmStatic
    fun isServerDesired(): Boolean = serverDesired

    @JvmStatic
    fun registerRestartAction(action: Runnable) {
        synchronized(lock) {
            restartAction = { action.run() }
        }
    }

    @JvmStatic
    fun unregister() {
        synchronized(lock) {
            restartAction = null
        }
    }

    /**
     * Posts a restart on the main thread, then blocks the caller until READY or timeout.
     *
     * @return true when the server reaches READY before [timeoutMs] elapses.
     */
    @JvmStatic
    fun requestRestartAndAwait(context: Context, timeoutMs: Long): Boolean {
        val mgr = ThumbnailServerManager.getInstance()
        if (mgr.getSyncState() == ThumbnailServerManager.ServerState.READY) {
            return true
        }
        val action = synchronized(lock) {
            if (serverDesired) restartAction else null
        }
        if (action == null) {
            SyncLog.info(
                context.applicationContext,
                LOG_TAG,
                "event=explorerRestartSkip reason=noHost desired=$serverDesired",
            )
            return false
        }
        SyncLog.info(
            context.applicationContext,
            LOG_TAG,
            "event=explorerRestartRequested awaitMs=$timeoutMs",
        )
        Handler(Looper.getMainLooper()).post { action() }
        return mgr.awaitReady(timeoutMs)
    }

    /** Main-thread restart when the observer sees STOPPED while the explorer still wants a lease. */
    @JvmStatic
    fun requestRestartIfNeeded() {
        val action = synchronized(lock) {
            if (serverDesired) restartAction else null
        } ?: return
        Handler(Looper.getMainLooper()).post { action() }
    }
}
