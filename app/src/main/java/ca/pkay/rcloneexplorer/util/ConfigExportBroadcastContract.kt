package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.BuildConfig

object ConfigExportBroadcastContract {
    const val ACTION = BuildConfig.APPLICATION_ID + ".EXPORT_CONFIG"
    const val EXTRA_EXPORT_PATH = "export_path"
    const val EXTRA_EXPORT_FILENAME = "export_filename"
}
