package ca.pkay.rcloneexplorer.util

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import ca.pkay.rcloneexplorer.R
import es.dmoral.toasty.Toasty

object PathLockBiometricHelper {

    /**
     * Long labels (remote names plus formatting) must stay short so [BiometricPrompt.PromptInfo.Builder]
     * validation does not throw on OEM builds with strict limits.
     */
    private const val REMOTE_LABEL_MAX_CHARS = 48

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
        val labelForPrompt =
            if (remoteLabel.length <= REMOTE_LABEL_MAX_CHARS) {
                remoteLabel
            } else {
                remoteLabel.substring(0, REMOTE_LABEL_MAX_CHARS - 1) + "\u2026"
            }
        val builder =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(
                    activity.getString(R.string.path_lock_biometric_title, labelForPrompt),
                )
                .setSubtitle(activity.getString(R.string.path_lock_biometric_subtitle))
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
        val promptInfo =
            try {
                builder.build()
            } catch (ex: Exception) {
                SyncLog.error(
                    activity,
                    "pathLockPromptBuild",
                    ex.stackTraceToString().replace('\n', ' ').take(1800),
                )
                Toasty.error(
                    activity,
                    activity.getString(R.string.path_lock_error),
                    android.widget.Toast.LENGTH_SHORT,
                    true,
                ).show()
                return
            }

        /*
         * scheduleFetchDirectoryContent triggers this from a Lifecycle onResume observer while
         * FragmentManager is still executing the fragment resume transaction.
         * BiometricPrompt.authenticate runs executePendingTransactions and throws
         * IllegalStateException("FragmentManager is already executing transactions") unless we
         * run it on the next main-loop turn via decorView.post.
         */
        val host = activity
        host.window.decorView.post {
            if (host.isFinishing) {
                return@post
            }
            if (!host.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@post
            }
            try {
                prompt.authenticate(promptInfo)
            } catch (ex: Exception) {
                SyncLog.error(
                    host,
                    "pathLockBiometricAuthenticate",
                    ex.stackTraceToString().replace('\n', ' ').take(1800),
                )
                Toasty.error(
                    host,
                    host.getString(R.string.path_lock_error),
                    android.widget.Toast.LENGTH_SHORT,
                    true,
                ).show()
            }
        }
    }
}
