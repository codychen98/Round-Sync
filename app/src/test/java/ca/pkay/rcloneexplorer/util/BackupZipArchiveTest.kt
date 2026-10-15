package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class BackupZipArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun plainZip_roundTrip_withoutPassword() {
        val zip = temp.newFile("plain.zip")
        FileOutputStream(zip).use { out ->
            BackupZipArchive.write(out, password = null) {
                writeBytes("rclone.conf", "type = local\n".toByteArray(StandardCharsets.UTF_8))
                writeBytes("rcx.prefs", "{\"showThumbnails\":true}".toByteArray(StandardCharsets.UTF_8))
            }
        }
        assertFalse(BackupZipArchive.isEncrypted(zip))
        assertEquals(
            "type = local\n",
            BackupZipArchive.readEntryText(zip, "rclone.conf", null),
        )
    }

    @Test
    fun encryptedZip_roundTrip_withPassword() {
        val zip = temp.newFile("enc.zip")
        val password = "correct-horse-battery"
        FileOutputStream(zip).use { out ->
            BackupZipArchive.write(out, password) {
                writeBytes("rclone.conf", "secret-config".toByteArray(StandardCharsets.UTF_8))
            }
        }
        assertTrue(BackupZipArchive.isEncrypted(zip))
        assertEquals(
            "secret-config",
            BackupZipArchive.readEntryText(zip, "rclone.conf", password),
        )
    }

    @Test
    fun encryptedZip_wrongPassword_throws() {
        val zip = temp.newFile("enc-wrong.zip")
        FileOutputStream(zip).use { out ->
            BackupZipArchive.write(out, "right-password") {
                writeBytes("rclone.conf", "data".toByteArray(StandardCharsets.UTF_8))
            }
        }
        try {
            BackupZipArchive.readEntryText(zip, "rclone.conf", "wrong-password")
            fail("Expected BackupZipWrongPasswordException")
        } catch (_: BackupZipWrongPasswordException) {
            // expected
        }
    }

    @Test
    fun encryptedZip_missingPassword_throwsRequired() {
        val zip = temp.newFile("enc-missing.zip")
        FileOutputStream(zip).use { out ->
            BackupZipArchive.write(out, "secret") {
                writeBytes("rclone.conf", "data".toByteArray(StandardCharsets.UTF_8))
            }
        }
        try {
            BackupZipArchive.extractEntryToFile(
                zip,
                "rclone.conf",
                temp.newFile("out.conf"),
                null,
            )
            fail("Expected BackupZipPasswordRequiredException")
        } catch (_: BackupZipPasswordRequiredException) {
            // expected
        }
    }

    @Test
    fun writeFile_entry_roundTrip() {
        val zip = temp.newFile("file-entry.zip")
        val source = temp.newFile("blob.bin").also { it.writeText("blob-bytes") }
        FileOutputStream(zip).use { out ->
            BackupZipArchive.write(out, "pw") {
                writeFile("cache/thumbnails/a/0", source)
            }
        }
        val dest = temp.newFile("restored.bin")
        BackupZipArchive.extractEntryToFile(zip, "cache/thumbnails/a/0", dest, "pw")
        assertEquals("blob-bytes", dest.readText())
    }
}
