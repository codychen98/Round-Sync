package ca.pkay.rcloneexplorer.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Unlocked remotes for path lock: cleared when [Intent.ACTION_SCREEN_OFF] is broadcast.
 *
 * The receiver uses the application context and is removed only when the hosting activity calls
 * [detach] from [Activity.onDestroy], so it stays registered across [Activity.onStop] while the user
 * locks the device or opens another activity — avoiding missing SCREEN_OFF due to unregistering in
 * onStop.
 */
object RemotePathUnlockSession {

    private val unlockedRemotes =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var screenOffReceiver: BroadcastReceiver? = null

    /** Application context used to register [Intent.ACTION_SCREEN_OFF]. */
    private var receiverContext: Context? = null

    private var registeredActivity: Activity? = null

    @JvmStatic
    fun markUnlocked(remoteName: String) {
        unlockedRemotes.add(remoteName)
    }

    @JvmStatic
    fun isUnlocked(remoteName: String): Boolean {
        return unlockedRemotes.contains(remoteName)
    }

    @JvmStatic
    fun clearAll() {
        unlockedRemotes.clear()
    }

    @JvmStatic
    fun attach(activity: Activity) {
        if (registeredActivity === activity && screenOffReceiver != null) {
            return
        }
        detachRegistered()
        registeredActivity = activity
        val app = activity.applicationContext
        receiverContext = app
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (Intent.ACTION_SCREEN_OFF == intent?.action) {
                        clearAll()
                    }
                }
            }
        screenOffReceiver = receiver
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @JvmStatic
    fun detach(activity: Activity) {
        if (registeredActivity !== activity) {
            return
        }
        detachRegistered()
    }

    private fun detachRegistered() {
        val app = receiverContext
        receiverContext = null
        val receiver = screenOffReceiver
        screenOffReceiver = null
        registeredActivity = null
        if (app != null && receiver != null) {
            try {
                app.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
