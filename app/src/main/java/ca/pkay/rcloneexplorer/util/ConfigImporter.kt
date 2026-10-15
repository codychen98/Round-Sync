package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.AppShortcutsHelper
import ca.pkay.rcloneexplorer.Database.json.Importer
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup
import ca.pkay.rcloneexplorer.Glide.ThumbnailCachePoisonPurge
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.RemoteConfig.RemoteConfigHelper
import ca.pkay.rcloneexplorer.workmanager.MediaFolderPolicyThumbnailPrefetchWorker
import java.io.File
import java.io.IOException

object ConfigImporter {

    enum class Status {
        SUCCESS,
        FAILURE_UNSPECIFIED,
        FAILURE_RCLONE_CONF_NOT_VALID,
        FAILURE_ZIP_MISSING_CONF,
        FAILURE_ZIP_INVALID_CONF,
        FAILURE_ZIP_PASSWORD_REQUIRED,
        FAILURE_ZIP_PASSWORD_WRONG,
    }

    data class Result(
        val status: Status,
    )

    @JvmStatic
    @JvmOverloads
    fun importFromUri(
        context: Context,
        uri: Uri,
        mime: String?,
        zipPassword: String? = null,
    ): Result {
        val appContext = context.applicationContext
        val rclone = Rclone(appContext)
        return if (mime == "application/zip" || isZipUri(uri)) {
            importZip(appContext, rclone, uri, zipPassword)
        } else {
            importConf(appContext, rclone, uri)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun importFromFile(
        context: Context,
        file: File,
        zipPassword: String? = null,
    ): Result {
        val appContext = context.applicationContext
        val rclone = Rclone(appContext)
        return if (file.name.endsWith(".zip", ignoreCase = true)) {
            importZip(appContext, rclone, file, zipPassword)
        } else {
            importConf(appContext, rclone, file)
        }
    }

    @JvmStatic
    fun applyPostImportSideEffects(context: Context, rclone: Rclone) {
        val appContext = context.applicationContext
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        if (sharedPreferences.getBoolean(appContext.getString(R.string.pref_key_enable_saf), false)) {
            RemoteConfigHelper.enableSaf(appContext)
        }

        sharedPreferences.edit()
            .remove(appContext.getString(R.string.shared_preferences_pinned_remotes))
            .remove(appContext.getString(R.string.shared_preferences_drawer_pinned_remotes))
            .remove(appContext.getString(R.string.shared_preferences_hidden_remotes))
            .remove(appContext.getString(R.string.pref_key_accessible_storage_locations))
            .apply()

        if (!rclone.isConfigEncrypted) {
            AppShortcutsHelper.removeAllAppShortcuts(appContext)
            AppShortcutsHelper.populateAppShortcuts(appContext, rclone.remotes)
        }

        // Restored blobs may include undecodable SVG placeholders; drop them (and reopen the
        // disk cache from the restored journal) before the prefetch worker sees the cache.
        ThumbnailCachePoisonPurge.runAfterImport(appContext)
        MediaFolderPolicyThumbnailPrefetchWorker.enqueueAfterImport(appContext)
    }

    private fun importZip(
        context: Context,
        rclone: Rclone,
        uri: Uri,
        zipPassword: String?,
    ): Result {
        return try {
            BackupZipArchive.materialize(context, uri).use { materialized ->
                importZip(context, rclone, materialized.file, zipPassword)
            }
        } catch (e: BackupZipPasswordRequiredException) {
            Result(Status.FAILURE_ZIP_PASSWORD_REQUIRED)
        } catch (e: BackupZipWrongPasswordException) {
            Result(Status.FAILURE_ZIP_PASSWORD_WRONG)
        } catch (e: Exception) {
            FLog.e(TAG, "Zip import failed for uri %s", e, uri)
            Result(Status.FAILURE_ZIP_MISSING_CONF)
        }
    }

    private fun importZip(
        context: Context,
        rclone: Rclone,
        file: File,
        zipPassword: String?,
    ): Result {
        return try {
            val encrypted = BackupZipArchive.isEncrypted(file)
            val password = resolveZipPassword(context, encrypted, zipPassword)
            if (encrypted && password.isNullOrEmpty()) {
                return Result(Status.FAILURE_ZIP_PASSWORD_REQUIRED)
            }

            if (!rclone.copyConfigFileFromZip(file, password)) {
                return Result(Status.FAILURE_ZIP_INVALID_CONF)
            }

            importOptionalZipEntries(context, rclone, file, password)
            applyPostImportSideEffects(context, rclone)
            Result(Status.SUCCESS)
        } catch (e: BackupZipPasswordRequiredException) {
            Result(Status.FAILURE_ZIP_PASSWORD_REQUIRED)
        } catch (e: BackupZipWrongPasswordException) {
            Result(Status.FAILURE_ZIP_PASSWORD_WRONG)
        } catch (e: Exception) {
            FLog.e(TAG, "Zip import failed for file %s", e, file.absolutePath)
            Result(Status.FAILURE_ZIP_MISSING_CONF)
        }
    }

    /**
     * Saved settings password is used automatically. A one-shot [providedPassword] from an import
     * prompt is used only for that import and is not persisted here.
     */
    private fun resolveZipPassword(
        context: Context,
        encrypted: Boolean,
        providedPassword: String?,
    ): String? {
        if (!encrypted) {
            return null
        }
        if (!providedPassword.isNullOrEmpty()) {
            return providedPassword
        }
        return BackupZipPasswordStore.getPasswordOrNull(context)
    }

    private fun importConf(context: Context, rclone: Rclone, uri: Uri): Result {
        return try {
            if (rclone.copyConfigFile(uri)) {
                applyPostImportSideEffects(context, rclone)
                Result(Status.SUCCESS)
            } else {
                Result(Status.FAILURE_RCLONE_CONF_NOT_VALID)
            }
        } catch (e: IOException) {
            FLog.e(TAG, "Conf import failed for uri %s", e, uri)
            Result(Status.FAILURE_RCLONE_CONF_NOT_VALID)
        }
    }

    private fun importConf(context: Context, rclone: Rclone, file: File): Result {
        return try {
            if (rclone.copyConfigFile(file)) {
                applyPostImportSideEffects(context, rclone)
                Result(Status.SUCCESS)
            } else {
                Result(Status.FAILURE_RCLONE_CONF_NOT_VALID)
            }
        } catch (e: IOException) {
            FLog.e(TAG, "Conf import failed for file %s", e, file.absolutePath)
            Result(Status.FAILURE_RCLONE_CONF_NOT_VALID)
        }
    }

    private fun importOptionalZipEntries(
        context: Context,
        rclone: Rclone,
        file: File,
        password: String?,
    ) {
        try {
            val json = rclone.readDatabaseJson(file, password)
            Importer.importJson(json, context)
        } catch (e: Exception) {
            // rcx.json missing or invalid - not fatal, old backups may not have it
        }

        try {
            val json = rclone.readSharedPrefs(file, password)
            SharedPreferencesBackup.importJson(json, context)
        } catch (e: Exception) {
            // rcx.prefs missing or invalid - not fatal, old backups may not have it
        }

        CacheArchiveImporter.extractFromZipFile(context, file, password)
    }

    private fun isZipUri(uri: Uri): Boolean {
        val path = uri.lastPathSegment ?: return false
        return path.endsWith(".zip", ignoreCase = true)
    }

    private const val TAG = "ConfigImporter"
}
