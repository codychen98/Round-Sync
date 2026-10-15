package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.Uri
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * zip4j-backed read/write for RoundSync backup archives.
 * When [password] is null/blank, writes and reads plain ZIPs compatible with existing backups.
 */
object BackupZipArchive {

    private const val COPY_BUFFER_BYTES = 8192
    private const val TEMP_PREFIX = "backup_zip_"
    private const val TEMP_SUFFIX = ".zip"

    interface EntrySink {
        fun writeFile(entryName: String, file: File)

        fun writeBytes(entryName: String, bytes: ByteArray, lastModifiedMs: Long = -1L)
    }

    @JvmStatic
    fun write(outputStream: OutputStream, password: String?, block: EntrySink.() -> Unit) {
        val encrypt = !password.isNullOrEmpty()
        val charPassword = if (encrypt) password!!.toCharArray() else null
        val zos = if (charPassword != null) {
            ZipOutputStream(outputStream, charPassword)
        } else {
            ZipOutputStream(outputStream)
        }
        try {
            val sink = object : EntrySink {
                override fun writeFile(entryName: String, file: File) {
                    val params = baseParams(entryName, encrypt)
                    val modified = file.lastModified()
                    if (modified > 0L) {
                        params.lastModifiedFileTime = modified
                    }
                    zos.putNextEntry(params)
                    try {
                        file.inputStream().use { input ->
                            copy(input, zos)
                        }
                    } finally {
                        zos.closeEntry()
                    }
                }

                override fun writeBytes(entryName: String, bytes: ByteArray, lastModifiedMs: Long) {
                    val params = baseParams(entryName, encrypt)
                    if (lastModifiedMs > 0L) {
                        params.lastModifiedFileTime = lastModifiedMs
                    }
                    zos.putNextEntry(params)
                    try {
                        zos.write(bytes)
                    } finally {
                        zos.closeEntry()
                    }
                }
            }
            sink.block()
        } finally {
            zos.close()
            if (charPassword != null) {
                charPassword.fill('\u0000')
            }
        }
    }

    @JvmStatic
    fun isEncrypted(file: File): Boolean {
        return try {
            ZipFile(file).use { it.isEncrypted }
        } catch (e: ZipException) {
            throw IOException("Could not open backup zip", e)
        }
    }

    @JvmStatic
    fun openZipFile(file: File, password: String?): ZipFile {
        val zipFile = ZipFile(file)
        if (zipFile.isEncrypted) {
            if (password.isNullOrEmpty()) {
                zipFile.close()
                throw BackupZipPasswordRequiredException()
            }
            zipFile.setPassword(password.toCharArray())
        }
        return zipFile
    }

    @JvmStatic
    fun extractEntryToFile(zipFile: File, entryName: String, targetFile: File, password: String?): File? {
        return try {
            openZipFile(zipFile, password).use { zip ->
                val header = zip.fileHeaders.firstOrNull { it.fileName == entryName } ?: return null
                if (header.isDirectory) {
                    return null
                }
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create parent for $targetFile")
                }
                zip.getInputStream(header).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        copy(input, output)
                    }
                }
                targetFile
            }
        } catch (e: BackupZipPasswordRequiredException) {
            throw e
        } catch (e: BackupZipWrongPasswordException) {
            throw e
        } catch (e: ZipException) {
            if (!password.isNullOrEmpty() || isWrongPassword(e)) {
                throw BackupZipWrongPasswordException(cause = e)
            }
            throw IOException("Could not extract $entryName from backup zip", e)
        }
    }

    @JvmStatic
    fun readEntryText(zipFile: File, entryName: String, password: String?): String? {
        val temp = File(zipFile.parentFile ?: File("."), "$entryName-read-tmp")
        return try {
            val extracted = extractEntryToFile(zipFile, entryName, temp, password) ?: return null
            extracted.readText(StandardCharsets.UTF_8)
        } finally {
            temp.delete()
        }
    }

    /**
     * Returns a readable File for [uri]. When the URI is already a local file, returns it and
     * [MaterializedZip.deleteOnClose] is false. Otherwise copies into cache and marks for delete.
     */
    @JvmStatic
    fun materialize(context: Context, uri: Uri): MaterializedZip {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.isFile) {
                    return MaterializedZip(file, deleteOnClose = false)
                }
            }
        }
        val app = context.applicationContext
        val temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, app.cacheDir)
        try {
            val input = app.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open backup zip")
            input.use { src ->
                FileOutputStream(temp).use { dst ->
                    copy(src, dst)
                }
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
        return MaterializedZip(temp, deleteOnClose = true)
    }

    data class MaterializedZip(
        val file: File,
        val deleteOnClose: Boolean,
    ) : AutoCloseable {
        override fun close() {
            if (deleteOnClose) {
                file.delete()
            }
        }
    }

    private fun baseParams(entryName: String, encrypt: Boolean): ZipParameters {
        val params = ZipParameters()
        params.fileNameInZip = entryName
        params.compressionMethod = CompressionMethod.DEFLATE
        if (encrypt) {
            params.isEncryptFiles = true
            params.encryptionMethod = EncryptionMethod.AES
            params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        return params
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read)
            }
            read = input.read(buffer)
        }
    }

    private fun isWrongPassword(e: ZipException): Boolean {
        val message = e.message?.lowercase().orEmpty()
        return message.contains("wrong password") ||
            message.contains("incorrect password") ||
            message.contains("password") && message.contains("invalid")
    }
}
