package ca.pkay.rcloneexplorer.util

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ca.pkay.rcloneexplorer.R
import es.dmoral.toasty.Toasty

object PathLockBiometricHelper {

    @JvmStatic
    fun requestUnlock(activity: FragmentActivity, remoteLabel: String, onSuccess: Runnable) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        onSuccess.run()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            Toasty.warning(
                                activity,
                                errString.toString(),
                                android.widget.Toast.LENGTH_SHORT,
                                true,
                            ).show()
                        }
                    }
                },
            )
        val builder =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.path_lock_biometric_title))
                .setSubtitle(
                    activity.getString(R.string.path_lock_biometric_subtitle) + "\n" + remoteLabel,
                )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder
                .setDeviceCredentialAllowed(true)
                .setNegativeButtonText(activity.getString(android.R.string.cancel))
        }
        try {
            prompt.authenticate(builder.build())
        } catch (_: Exception) {
            Toasty.error(
                activity,
                activity.getString(R.string.path_lock_error),
                android.widget.Toast.LENGTH_SHORT,
                true,
            ).show()
        }
    }
}
