package ca.pkay.rcloneexplorer.util

import java.io.IOException

/** Thrown when a backup ZIP is encrypted and no password is available. */
class BackupZipPasswordRequiredException(
    message: String = "Backup zip password required",
) : IOException(message)
