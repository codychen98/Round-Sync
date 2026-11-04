package ca.pkay.rcloneexplorer.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathLockScreenLockGuardTest {

    @Test
    fun availabilityFromCanAuthenticateIsFalseOnlyForNothingEnrolledOrMissingHardware() {
        assertFalse(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            ),
        )
        assertFalse(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            ),
        )
        assertFalse(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            ),
        )
        assertTrue(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_SUCCESS,
            ),
        )
        assertTrue(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            ),
        )
        assertTrue(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            ),
        )
        assertTrue(
            PathLockScreenLockGuard.availabilityFromCanAuthenticate(
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            ),
        )
    }

    @Test
    fun isNoCredentialErrorMatchesPromptCodesThatMeanNoUnlockMethod() {
        assertTrue(
            PathLockScreenLockGuard.isNoCredentialError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL),
        )
        assertTrue(
            PathLockScreenLockGuard.isNoCredentialError(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
        assertTrue(
            PathLockScreenLockGuard.isNoCredentialError(BiometricPrompt.ERROR_HW_NOT_PRESENT),
        )
        assertFalse(
            PathLockScreenLockGuard.isNoCredentialError(BiometricPrompt.ERROR_USER_CANCELED),
        )
        assertFalse(
            PathLockScreenLockGuard.isNoCredentialError(BiometricPrompt.ERROR_NEGATIVE_BUTTON),
        )
        assertFalse(
            PathLockScreenLockGuard.isNoCredentialError(BiometricPrompt.ERROR_LOCKOUT),
        )
    }
}
