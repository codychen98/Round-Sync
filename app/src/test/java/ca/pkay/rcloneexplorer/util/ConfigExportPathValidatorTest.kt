package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfigExportPathValidatorTest {

    @Test
    fun resolveExportDestination_usesTimestampDefaultFilenameWhenMissing() {
        val result = ConfigExportPathValidator.resolveExportDestination(
            exportPath = "/storage/emulated/0/Download/Backup",
            exportFilename = null,
            appPackageName = "de.felixnuesse.extract",
        )

        assertTrue(result.isSuccess)
        assertTrue(
            result.getOrThrow().outputFile.name.matches(
                Regex("RoundSync-backup-\\d{4}-\\d{2}-\\d{2}_\\d{6}\\.zip"),
            ),
        )
    }

    @Test
    fun resolveExportDestination_usesProvidedFilename() {
        val result = ConfigExportPathValidator.resolveExportDestination(
            exportPath = "/storage/emulated/0/Download/Backup",
            exportFilename = "RoundSync-backup.zip",
            appPackageName = "de.felixnuesse.extract",
        )

        assertTrue(result.isSuccess)
        val destination = result.getOrThrow()
        assertEquals(
            "/storage/emulated/0/Download/Backup/RoundSync-backup.zip",
            destination.outputFile.path,
        )
    }

    @Test
    fun resolveExportDestination_addsZipExtensionWhenMissing() {
        val result = ConfigExportPathValidator.resolveExportDestination(
            exportPath = "/storage/emulated/0/Download/Backup",
            exportFilename = "RoundSync-backup",
            appPackageName = "de.felixnuesse.extract",
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "RoundSync-backup.zip",
            result.getOrThrow().outputFile.name,
        )
    }

    @Test
    fun resolveExportDestination_rejectsBlankPath() {
        val result = ConfigExportPathValidator.resolveExportDestination(
            exportPath = "  ",
            exportFilename = "RoundSync-backup.zip",
            appPackageName = "de.felixnuesse.extract",
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun resolveExportDestination_rejectsFilenameWithPathSeparators() {
        val result = ConfigExportPathValidator.resolveExportDestination(
            exportPath = "/storage/emulated/0/Download/Backup",
            exportFilename = "../escape.zip",
            appPackageName = "de.felixnuesse.extract",
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun isAllowedExportDirectory_allowsDownloadPath() {
        assertTrue(
            ConfigExportPathValidator.isAllowedExportDirectory(
                File("/storage/emulated/0/Download/Sync Folder/PCloud Sync/Backup"),
                "de.felixnuesse.extract",
            ),
        )
    }

    @Test
    fun isAllowedExportDirectory_blocksOtherAppPrivateData() {
        assertFalse(
            ConfigExportPathValidator.isAllowedExportDirectory(
                File("/storage/emulated/0/Android/data/com.example/files"),
                "de.felixnuesse.extract",
            ),
        )
    }

    @Test
    fun isAllowedExportDirectory_allowsOwnAppDataDirectory() {
        assertTrue(
            ConfigExportPathValidator.isAllowedExportDirectory(
                File("/storage/emulated/0/Android/data/de.felixnuesse.extract/files"),
                "de.felixnuesse.extract",
            ),
        )
    }
}
