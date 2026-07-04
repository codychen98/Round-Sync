package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.BuildConfig

object ConfigImportBroadcastContract {
    const val ACTION = BuildConfig.APPLICATION_ID + ".IMPORT_CONFIG"
    const val EXTRA_IMPORT_PATH = "import_path"
    const val EXTRA_IMPORT_FILENAME = "import_filename"
}
