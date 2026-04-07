package ca.pkay.rcloneexplorer.Services;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.NotificationUtils;
import ca.pkay.rcloneexplorer.util.SyncLog;

/**
 * Foreground service that hosts the rclone HTTP serve process for thumbnail loading.
 *
 * Foreground {@link android.app.Service} using {@code START_STICKY} and a
 * silent low-priority notification. The actual process lifecycle is managed by
 * {@link ThumbnailServerManager}; this service is responsible only for the
 * Android service lifecycle and the foreground notification.</p>
 *
 * <p>SAFW remotes are skipped (they use a separate SAF-DAV server for thumbnails).</p>
 */
public class ThumbnailServerService extends android.app.Service {

    private static final String TAG = "ThumbnailServerService";

    public static final String ACTION_START = "ca.pkay.rcexplorer.ThumbnailServerService.ACTION_START";
    public static final String ACTION_STOP  = "ca.pkay.rcexplorer.ThumbnailServerService.ACTION_STOP";

    public static final String EXTRA_REMOTE = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_REMOTE";
    public static final String EXTRA_PORT   = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_PORT";
    public static final String EXTRA_AUTH   = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_AUTH";

    private static final String CHANNEL_ID   = "ca.pkay.rcexplorer.thumbnail_server_channel";
    private static final String CHANNEL_NAME = "Thumbnail server";
    private static final int    NOTIFICATION_ID = 182;

    private final Observer<ThumbnailServerManager.ServerState> stateObserver =
            state -> {
                if (state == ThumbnailServerManager.ServerState.STOPPED) {
                    FLog.d(TAG, "Manager stopped — stopping service");
                    stopForegroundCompat();
                    stopSelf();
                }
            };

    private boolean observerRegistered = false;

    // region — Static helpers for callers

    /** Starts the service and instructs the manager to begin serving thumbnails. */
    public static void startServing(Context context, RemoteItem remote, int port, String auth) {
        if (remote.isRemoteType(RemoteItem.SAFW)) {
            FLog.d(TAG, "Skipping SAFW remote — no thumbnail server needed");
            SyncLog.info(context, "ThumbnailServer",
                "Skipping SAFW remote - no thumbnail server needed");
            return;
        }
        SyncLog.info(context, "ThumbnailServer",
            "startServing: remote=" + remote.getName() + ", port=" + port);
        Intent intent = new Intent(context, ThumbnailServerService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_REMOTE, remote);
        intent.putExtra(EXTRA_PORT, port);
        intent.putExtra(EXTRA_AUTH, auth);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Instructs the service to stop the thumbnail server. */
    public static void stopServing(Context context) {
        Intent intent = new Intent(context, ThumbnailServerService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    // endregion

    // region — Service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationUtils.createNotificationChannel(
                this,
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
                getString(R.string.thumbnail_server_notification_channel_description));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        final String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            handleStart(intent);
        } else if (ACTION_STOP.equals(action)) {
            handleStop();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ThumbnailServerManager.getInstance().getState().removeObserver(stateObserver);
        observerRegistered = false;
        ThumbnailServerManager.getInstance().stop();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // endregion

    // region — Intent handlers

    private void handleStart(Intent intent) {
        RemoteItem remote = intent.getParcelableExtra(EXTRA_REMOTE);
        int port = intent.getIntExtra(EXTRA_PORT, 29179);
        String auth = intent.getStringExtra(EXTRA_AUTH);

        SyncLog.info(this, "ThumbnailServer",
            "Service handleStart: remote=" + (remote != null ? remote.getName() : "NULL")
            + ", port=" + port
            + ", auth=" + (auth != null ? "present" : "NULL"));

        if (remote == null || auth == null) {
            FLog.e(TAG, "handleStart: missing remote or auth — aborting");
            SyncLog.error(this, "ThumbnailServer",
                "handleStart ABORTED: missing remote or auth");
            stopSelf();
            return;
        }

        startForegroundWithNotification();

        ThumbnailServerManager manager = ThumbnailServerManager.getInstance();
        if (!observerRegistered) {
            manager.getState().observeForever(stateObserver);
            observerRegistered = true;
        }
        manager.start(this, remote, port, auth);
    }

    private void handleStop() {
        ThumbnailServerManager.getInstance().stop();
        // stateObserver will see STOPPED and call stopSelf(); but call it directly too
        // in case the manager was already stopped.
        stopForegroundCompat();
        stopSelf();
    }

    // endregion

    // region — Notification

    private void startForegroundWithNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streaming)
                .setContentTitle(getString(R.string.thumbnail_server_notification_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    // endregion
}
