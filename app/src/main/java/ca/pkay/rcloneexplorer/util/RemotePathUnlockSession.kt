package ca.pkay.rcloneexplorer.util

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Unlocked remotes for path lock: cleared when the device locks (keyguard) or the screen turns off.
 *
 * [ACTION_SCREEN_OFF] is registered on the application context and only unregistered when the
 * hosting activity [detach]es in [Activity.onDestroy], so the receiver remains active while
 * [Activity.onStop] runs (e.g. user locks the device or opens another activity). Previously
 * unregistering in [Activity.onStop] dropped the receiver before [ACTION_SCREEN_OFF] fired.
 */
object RemotePathUnlockSession {

    private val unlockedRemotes =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var screenOffReceiver: BroadcastReceiver? = null

    /** Application context used to register [ACTION_SCREEN_OFF]. */
    private var receiverContext: Context? = null

    private var registeredActivity: Activity? = null

    private var keyguardLockedListener: Consumer<Boolean>? = null

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
        registerKeyguardLockedStateListenerIfSupported(activity)
    }

    @JvmStatic
    fun detach(activity: Activity) {
        if (registeredActivity !== activity) {
            return
        }
        detachRegistered()
    }

    private fun registerKeyguardLockedStateListenerIfSupported(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) {
            return
        }
        val km = activity.getSystemService(KeyguardManager::class.java) ?: return
        val listener = Consumer<Boolean> { locked ->
            if (locked) {
                clearAll()
            }
        }
        keyguardLockedListener = listener
        km.registerKeyguardLockedStateListener(
            ContextCompat.getMainExecutor(activity),
            listener,
        )
    }

    private fun detachRegistered() {
        unregisterKeyguardLockedStateListenerIfSupportedBeforeClearingActivity()
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

    private fun unregisterKeyguardLockedStateListenerIfSupportedBeforeClearingActivity() {
        if (Build.VERSION.SDK_INT < 34) {
            return
        }
        val listener = keyguardLockedListener ?: return
        val act = registeredActivity ?: return
        val km = act.getSystemService(KeyguardManager::class.java) ?: return
        keyguardLockedListener = null
        try {
            km.unregisterKeyguardLockedStateListener(listener)
        } catch (_: RuntimeException) {
        }
    }
}
