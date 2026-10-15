package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the optional backup-ZIP password in EncryptedSharedPreferences.
 * Cleared with app data. Never written into [ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup].
 */
object BackupZipPasswordStore {

    private const val PREFS_FILE = "backup_zip_password_prefs"
    private const val KEY_PASSWORD = "backup_zip_password"

    @JvmStatic
    fun hasPassword(context: Context): Boolean {
        val value = getPasswordOrNull(context)
        return !value.isNullOrEmpty()
    }

    @JvmStatic
    fun getPasswordOrNull(context: Context): String? {
        return try {
            val password = prefs(context).getString(KEY_PASSWORD, null)
            if (password.isNullOrEmpty()) null else password
        } catch (t: Throwable) {
            FLog.e(TAG, "Failed to read backup zip password", t)
            null
        }
    }

    @JvmStatic
    fun setPassword(context: Context, password: String) {
        val trimmed = password.trim()
        require(trimmed.isNotEmpty()) { "Backup zip password must not be empty" }
        prefs(context).edit().putString(KEY_PASSWORD, trimmed).apply()
    }

    @JvmStatic
    fun clearPassword(context: Context) {
        prefs(context).edit().remove(KEY_PASSWORD).apply()
    }

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private const val TAG = "BackupZipPasswordStore"
}
