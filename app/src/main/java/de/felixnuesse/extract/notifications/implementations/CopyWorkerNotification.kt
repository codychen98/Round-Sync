package de.felixnuesse.extract.notifications.implementations

import android.content.Context
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.notifications.prototypes.WorkerNotification
import ca.pkay.rcloneexplorer.notifications.support.StatusObject

class CopyWorkerNotification(var context: Context) : WorkerNotification(context) {

    override val CHANNEL_ID = "de.felixnuesse.extract.copy_service"

    override val initialTitle = string(R.string.worker_copy_initialtitle)
    override val serviceOngoingTitle = initialTitle
    override val serviceFailed = string(R.string.worker_copy_failed)
    override val serviceCancelled = "unused"
    override val serviceSuccess = string(R.string.worker_copy_success)

    // use only a single channel
    override val CHANNEL_SUCCESS_ID = CHANNEL_ID
    override val CHANNEL_FAIL_ID = CHANNEL_ID

    override val channel_ongoing_title = string(R.string.copy_service_notification_title)
    override val channel_ongoing_description = string(R.string.copy_service_notification_description)
    override val channel_success_title = channel_ongoing_title
    override val channel_success_description = channel_ongoing_description
    override val channel_failed_title = channel_ongoing_title
    override val channel_failed_description = channel_ongoing_description

    override val PERSISTENT_NOTIFICATION_ID = 49

    override val SUMMARY_ID = 50

    override fun generateSuccessMessage(statusObject: StatusObject, fileItem: FileItem): String {
        return mContext.resources.getQuantityString(
                R.plurals.worker_copy_success_message,
                statusObject.getDeletions(),
                fileItem.name
        )
    }

}
