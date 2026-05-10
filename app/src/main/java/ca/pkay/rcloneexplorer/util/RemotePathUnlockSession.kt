package ca.pkay.rcloneexplorer.util

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Remotes unlocked for the current keyguard session. Cleared when the device locks
 * ([KeyguardManager.KeyguardLockedStateListener], API 33+) or the screen turns off (fallback on
 * older API levels).
 */
object RemotePathUnlockSession {

    private val unlockedRemotes =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var screenOffReceiver: BroadcastReceiver? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val km = activity.getSystemService(KeyguardManager::class.java) ?: return
            val listener =
                KeyguardManager.KeyguardLockedStateListener { isKeyguardLocked ->
                    if (isKeyguardLocked) {
                        clearAll()
                    }
                }
            keyguardListener = listener
            km.registerKeyguardLockedStateListener(activity.mainExecutor, listener)
        } else {
            registerScreenOffReceiver(activity)
        }
    }

    private fun registerScreenOffReceiver(activity: Activity) {
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
        activity.registerReceiver(receiver, filter)
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
        if (act == null) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val km = act.getSystemService(KeyguardManager::class.java)
            val listener = keyguardListener
            keyguardListener = null
            if (km != null && listener != null) {
                km.unregisterKeyguardLockedStateListener(listener)
            }
        } else {
            val receiver = screenOffReceiver
            screenOffReceiver = null
            if (receiver != null) {
                try {
                    act.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }
}
