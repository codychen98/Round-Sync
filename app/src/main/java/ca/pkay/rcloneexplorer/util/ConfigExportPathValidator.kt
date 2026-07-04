package ca.pkay.rcloneexplorer.util

import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object ConfigExportPathValidator {

    private val SAFE_FILENAME = Pattern.compile("^[A-Za-z0-9._-]+$")
    private val OTHER_APP_ANDROID_DATA = Pattern.compile(
        "^/storage/(?:emulated/\\d+|[^/]+)/Android/data/[^/]+/.*",
    )

    data class ResolvedExportDestination(
        val directory: File,
        val outputFile: File,
    )

    @JvmStatic
    fun resolveExportDestination(
        exportPath: String?,
        exportFilename: String?,
        appPackageName: String,
    ): Result<ResolvedExportDestination> {
        if (exportPath.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("export_path is required"))
        }
        if (exportPath.contains('\u0000')) {
            return Result.failure(IllegalArgumentException("export_path is invalid"))
        }

        val directory = File(exportPath.trim())
        val canonicalDirectory = try {
            directory.canonicalFile
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("export_path is invalid", e))
        }

        if (!isAllowedExportDirectory(canonicalDirectory, appPackageName)) {
            return Result.failure(SecurityException("export_path is not allowed"))
        }

        val filename = sanitizeFilename(exportFilename)
            ?: defaultFilename()

        val outputFile = File(canonicalDirectory, filename)
        val canonicalOutput = try {
            outputFile.canonicalFile
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("export_filename is invalid", e))
        }

        val outputParent = canonicalOutput.parentFile?.canonicalFile
        if (outputParent == null || outputParent != canonicalDirectory) {
            return Result.failure(SecurityException("export_filename escapes export_path"))
        }

        return Result.success(ResolvedExportDestination(canonicalDirectory, canonicalOutput))
    }

    @JvmStatic
    fun sanitizeFilename(exportFilename: String?): String? {
        val trimmed = exportFilename?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000') || trimmed.contains("..")) {
            return null
        }
        if (!SAFE_FILENAME.matcher(trimmed).matches()) {
            return null
        }
        return if (trimmed.endsWith(".zip", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed.zip"
        }
    }

    @JvmStatic
    fun defaultFilename(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            .format(Calendar.getInstance().time)
        return "RoundSync-backup-$timestamp.zip"
    }

    @JvmStatic
    fun isAllowedExportDirectory(directory: File, appPackageName: String): Boolean {
        if (!directory.isAbsolute) {
            return false
        }

        val canonicalPath = directory.canonicalPath
        if (canonicalPath.startsWith("/data/") || canonicalPath.startsWith("/system/")) {
            return false
        }

        val ownAndroidDataPrefix = "/storage/emulated/0/Android/data/$appPackageName"
        if (canonicalPath.startsWith(ownAndroidDataPrefix)) {
            return true
        }

        if (OTHER_APP_ANDROID_DATA.matcher(canonicalPath).matches()) {
            return false
        }

        val externalRoot = Environment.getExternalStorageDirectory()?.canonicalPath
        if (externalRoot != null && (canonicalPath == externalRoot || canonicalPath.startsWith("$externalRoot/"))) {
            return true
        }

        return canonicalPath.startsWith("/storage/emulated/") ||
            canonicalPath.matches(Regex("^/storage/[0-9A-Fa-f-]+(?:/.*)?$"))
    }
}
