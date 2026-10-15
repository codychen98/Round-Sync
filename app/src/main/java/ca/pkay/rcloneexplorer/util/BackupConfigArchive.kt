package ca.pkay.rcloneexplorer.util

import android.content.Context
import ca.pkay.rcloneexplorer.Database.json.Exporter
import ca.pkay.rcloneexplorer.Database.json.SharedPreferencesBackup
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Writes a RoundSync backup ZIP (config, prefs, DB JSON, optional thumbnail cache).
 * Uses AES-256 via zip4j when [BackupZipPasswordStore] has a password; otherwise plain ZIP.
 */
object BackupConfigArchive {

    @JvmStatic
    fun writeToStream(context: Context, outputStream: OutputStream) {
        val app = context.applicationContext
        try {
            val password = BackupZipPasswordStore.getPasswordOrNull(app)
            val configBytes = readLocalConfigBytes(app)
            val databaseJson = Exporter.create(app)
            val prefsJson = SharedPreferencesBackup.export(app)
            BackupZipArchive.write(outputStream, password) {
                writeBytes("rcx.json", databaseJson.toByteArray(StandardCharsets.UTF_8))
                writeBytes("rcx.prefs", prefsJson.toByteArray(StandardCharsets.UTF_8))
                writeBytes("rclone.conf", configBytes)
                CacheArchiveExporter.appendCacheEntries(app, this)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Unable to write config export zip", e)
        }
    }

    private fun readLocalConfigBytes(context: Context): ByteArray {
        val configFile = File(context.filesDir, "rclone.conf")
        if (!configFile.isFile) {
            throw IOException("Could not read config file")
        }
        return configFile.readBytes()
    }
}
