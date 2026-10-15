package ca.pkay.rcloneexplorer.util

import java.io.IOException

/** Thrown when an AES-encrypted backup ZIP rejects the supplied password. */
class BackupZipWrongPasswordException(
    message: String = "Wrong backup zip password",
    cause: Throwable? = null,
) : IOException(message, cause)
