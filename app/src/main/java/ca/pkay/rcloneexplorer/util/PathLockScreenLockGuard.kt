package ca.pkay.rcloneexplorer.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import ca.pkay.rcloneexplorer.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.dmoral.toasty.Toasty

/** Path lock needs a device credential or biometric; this explains the fail-closed state and how to fix it. */
object PathLockScreenLockGuard {

    private val AUTHENTICATORS: Int =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    enum class Reason { UNLOCK, ENABLED_WITHOUT_LOCK, RESTORED }

    @JvmStatic
    fun isScreenLockAvailable(context: Context): Boolean {
        return try {
            availabilityFromCanAuthenticate(BiometricManager.from(context).canAuthenticate(AUTHENTICATORS))
        } catch (t: Throwable) {
            FLog.e(TAG, "canAuthenticate failed; assuming available", t)
            true
        }
    }

    /** Only "nothing enrolled / no hardware" means unavailable; unknown states let the prompt decide. */
    internal fun availabilityFromCanAuthenticate(code: Int): Boolean {
        return when (code) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> false
            else -> true
        }
    }

    internal fun isNoCredentialError(errorCode: Int): Boolean {
        return when (errorCode) {
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT -> true
            else -> false
        }
    }

    @JvmStatic
    fun showScreenLockRequiredDialog(activity: Activity, remoteLabel: String?, reason: Reason) {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val label = remoteLabel ?: ""
        val message = when (reason) {
            Reason.UNLOCK -> activity.getString(R.string.path_lock_no_screen_lock_unlock, label)
            Reason.ENABLED_WITHOUT_LOCK -> activity.getString(R.string.path_lock_no_screen_lock_enabled, label)
            Reason.RESTORED -> activity.getString(R.string.path_lock_no_screen_lock_restored)
        }
        val negativeText = if (reason == Reason.UNLOCK) R.string.cancel else R.string.ok
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.path_lock_no_screen_lock_title)
            .setMessage(message)
            .setPositiveButton(R.string.path_lock_set_up_screen_lock) { _, _ -> openScreenLockSettings(activity) }
            .setNegativeButton(negativeText, null)
            .show()
    }

    private fun openScreenLockSettings(activity: Activity) {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        try {
            activity.startActivity(primary)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Toasty.error(activity, activity.getString(R.string.path_lock_error), Toast.LENGTH_SHORT, true).show()
            }
        }
    }

    private const val TAG = "PathLockScreenLockGuard"
}
