package ca.pkay.rcloneexplorer.util

import android.os.Environment
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object ConfigBackupPathValidator {

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

        if (!isAllowedBackupDirectory(canonicalDirectory, appPackageName)) {
            return Result.failure(SecurityException("export_path is not allowed"))
        }

        val filename = sanitizeFilename(exportFilename)
            ?: defaultExportFilename()

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
    fun resolveImportSource(
        importPath: String?,
        importFilename: String?,
        appPackageName: String,
    ): Result<File> {
        if (importPath.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("import_path is required"))
        }
        if (importPath.contains('\u0000')) {
            return Result.failure(IllegalArgumentException("import_path is invalid"))
        }
        if (importFilename.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("import_filename is required"))
        }

        val directory = File(importPath.trim())
        val canonicalDirectory = try {
            directory.canonicalFile
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("import_path is invalid", e))
        }

        if (!isAllowedBackupDirectory(canonicalDirectory, appPackageName)) {
            return Result.failure(SecurityException("import_path is not allowed"))
        }

        val filename = sanitizeFilename(importFilename)
            ?: return Result.failure(IllegalArgumentException("import_filename is invalid"))

        val importFile = File(canonicalDirectory, filename)
        val canonicalImportFile = try {
            importFile.canonicalFile
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("import_filename is invalid", e))
        }

        val importParent = canonicalImportFile.parentFile?.canonicalFile
        if (importParent == null || importParent != canonicalDirectory) {
            return Result.failure(SecurityException("import_filename escapes import_path"))
        }

        if (!canonicalImportFile.isFile) {
            return Result.failure(
                FileNotFoundException(
                    "import file does not exist: ${canonicalImportFile.absolutePath}",
                ),
            )
        }
        if (!canonicalImportFile.canRead()) {
            return Result.failure(
                IOException("import file is not readable: ${canonicalImportFile.absolutePath}"),
            )
        }

        return Result.success(canonicalImportFile)
    }

    @JvmStatic
    fun sanitizeFilename(filename: String?): String? {
        val trimmed = filename?.trim().orEmpty()
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
    fun defaultExportFilename(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            .format(Calendar.getInstance().time)
        return "RoundSync-backup-$timestamp.zip"
    }

    @JvmStatic
    fun isAllowedBackupDirectory(directory: File, appPackageName: String): Boolean {
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
