package ca.pkay.rcloneexplorer.util

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Remotes unlocked for the current session until the screen turns off ([Intent.ACTION_SCREEN_OFF]),
 * which approximates "device locked" for path-lock purposes without relying on KeyguardManager APIs
 * that vary across compile SDK stubs.
 */
object RemotePathUnlockSession {

    private val unlockedRemotes =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var screenOffReceiver: BroadcastReceiver? = null

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
        if (registeredActivity === activity) {
            return
        }
        detachRegistered()
        registeredActivity = activity
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (Intent.ACTION_SCREEN_OFF == intent?.action) {
                        clearAll()
                    }
                }
            }
        screenOffReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
    }

    @JvmStatic
    fun detach(activity: Activity) {
        if (registeredActivity !== activity) {
            return
        }
        detachRegistered()
    }

    private fun detachRegistered() {
        val act = registeredActivity
        registeredActivity = null
        val receiver = screenOffReceiver
        screenOffReceiver = null
        if (act != null && receiver != null) {
            try {
                act.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
