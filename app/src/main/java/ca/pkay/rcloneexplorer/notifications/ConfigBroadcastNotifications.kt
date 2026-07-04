package ca.pkay.rcloneexplorer.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.NotificationUtils

object ConfigBroadcastNotifications {

    private const val CHANNEL_ID = "config_broadcast"
    private const val IMPORT_SUCCESS_NOTIFICATION_ID = 9101
    private const val IMPORT_FAILURE_NOTIFICATION_ID = 9102

    fun showImportSuccess(context: Context, encryptedConfig: Boolean) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val title = appContext.getString(R.string.config_import_broadcast_success_title)
        val message = if (encryptedConfig) {
            appContext.getString(R.string.config_import_broadcast_success_encrypted_message)
        } else {
            appContext.getString(R.string.config_import_broadcast_success_message)
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_import)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppPendingIntent(appContext))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationUtils.createNotification(appContext, IMPORT_SUCCESS_NOTIFICATION_ID, notification)
    }

    fun showImportFailure(context: Context, message: String) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_import)
            .setContentTitle(appContext.getString(R.string.config_import_broadcast_failure_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openAppPendingIntent(appContext))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationUtils.createNotification(appContext, IMPORT_FAILURE_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        NotificationUtils.createNotificationChannel(
            context,
            CHANNEL_ID,
            context.getString(R.string.config_broadcast_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
            context.getString(R.string.config_broadcast_notification_channel_description),
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
