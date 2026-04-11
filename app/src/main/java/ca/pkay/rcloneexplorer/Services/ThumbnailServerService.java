package ca.pkay.rcloneexplorer.Services;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.BackgroundMediaPrepWorkTracker;
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

    public static final String ACTION_START           = "ca.pkay.rcexplorer.ThumbnailServerService.ACTION_START";
    public static final String ACTION_STOP            = "ca.pkay.rcexplorer.ThumbnailServerService.ACTION_STOP";
    public static final String ACTION_UPDATE_PROGRESS = "ca.pkay.rcexplorer.ThumbnailServerService.ACTION_UPDATE_PROGRESS";
    public static final String ACTION_CLEAR_PROGRESS  = "ca.pkay.rcexplorer.ThumbnailServerService.ACTION_CLEAR_PROGRESS";

    public static final String EXTRA_REMOTE = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_REMOTE";
    public static final String EXTRA_PORT   = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_PORT";
    public static final String EXTRA_AUTH   = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_AUTH";
    public static final String EXTRA_LOADED = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_LOADED";
    public static final String EXTRA_TOTAL  = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_TOTAL";
    /** Rclone-style path for the folder line (e.g. {@code //remote/Photos}). */
    public static final String EXTRA_FOLDER_DISPLAY = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_FOLDER_DISPLAY";
    public static final String EXTRA_CACHE_LOADED = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_CACHE_LOADED";
    public static final String EXTRA_CACHE_TOTAL  = "ca.pkay.rcexplorer.ThumbnailServerService.EXTRA_CACHE_TOTAL";

    /** Shared with {@code LastFolderThumbnailPrefetchWorker} WorkManager foreground (Q16 / E.3). */
    public static final String NOTIFICATION_CHANNEL_ID = "ca.pkay.rcexplorer.thumbnail_server_channel";
    private static final int    NOTIFICATION_ID = 182;

    private boolean observerRegistered = false;
    private boolean serverEverStarted = false;
    private NotificationManager notificationManager;
    /** Last folder label shown in the media-prep notification body. */
    private String folderDisplayPath = "";
    private int lastThumbLoaded;
    private int lastThumbTotal;
    private int lastCacheLoaded;
    private int lastCacheTotal;

    private final Observer<ThumbnailServerManager.ServerState> stateObserver =
            state -> {
                if (state == ThumbnailServerManager.ServerState.STARTING
                        || state == ThumbnailServerManager.ServerState.READY) {
                    serverEverStarted = true;
                } else if (state == ThumbnailServerManager.ServerState.STOPPED) {
                    if (serverEverStarted) {
                        // Genuine stop after the server was running — shut the service down.
                        FLog.d(TAG, "Manager stopped — stopping service");
                        SyncLog.info(this, "ThumbnailServer",
                            "Service: manager reached STOPPED after running — stopping self");
                        stopForegroundCompat();
                        if (notificationManager != null) {
                            notificationManager.cancel(NOTIFICATION_ID);
                        }
                        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(false);
                        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(false);
                        stopSelf();
                    } else {
                        // LiveData delivered the initial STOPPED value on observer registration.
                        // Ignore it; the server hasn't started yet.
                        SyncLog.info(this, "ThumbnailServer",
                            "Service: ignoring initial STOPPED delivery (server not yet started)");
                    }
                }
            };

    // region — Static helpers for callers

    /** Starts the service and instructs the manager to begin serving thumbnails. */
    public static void startServing(Context context, RemoteItem remote, int port, String auth) {
        startServing(context, remote, port, auth, null);
    }

    /**
     * @param folderDisplayPath optional folder path for notification (e.g. {@code //remote/dir});
     *                          if null or empty, {@code //}{@linkplain RemoteItem#getName() remote} is used.
     */
    public static void startServing(Context context, RemoteItem remote, int port, String auth,
            @Nullable String folderDisplayPath) {
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
        if (folderDisplayPath != null && !folderDisplayPath.isEmpty()) {
            intent.putExtra(EXTRA_FOLDER_DISPLAY, folderDisplayPath);
        }
        ContextCompat.startForegroundService(context, intent);
    }

    /** Instructs the service to stop the thumbnail server. */
    public static void stopServing(Context context) {
        Intent intent = new Intent(context, ThumbnailServerService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /** Updates the foreground notification with thumbnail loading progress. */
    public static void updateProgress(Context context, int loaded, int total) {
        updateProgress(context, null, loaded, total, 0, 0);
    }

    /**
     * @param folderDisplayPath if non-null and non-empty, updates the stored folder line for this session
     * @param cacheLoaded        when {@code cacheTotal > 0}, shows the Cache progress line (reserved for Phase F)
     * @param cacheTotal         when {@code > 0}, cache work is considered active for notification copy
     */
    public static void updateProgress(Context context, @Nullable String folderDisplayPath,
            int thumbnailLoaded, int thumbnailTotal, int cacheLoaded, int cacheTotal) {
        Intent intent = new Intent(context, ThumbnailServerService.class);
        intent.setAction(ACTION_UPDATE_PROGRESS);
        intent.putExtra(EXTRA_LOADED, thumbnailLoaded);
        intent.putExtra(EXTRA_TOTAL, thumbnailTotal);
        if (folderDisplayPath != null && !folderDisplayPath.isEmpty()) {
            intent.putExtra(EXTRA_FOLDER_DISPLAY, folderDisplayPath);
        }
        intent.putExtra(EXTRA_CACHE_LOADED, cacheLoaded);
        intent.putExtra(EXTRA_CACHE_TOTAL, cacheTotal);
        context.startService(intent);
    }

    /** Resets the foreground notification to the idle media-prep body (folder line only, no progress lines). */
    public static void clearProgress(Context context) {
        Intent intent = new Intent(context, ThumbnailServerService.class);
        intent.setAction(ACTION_CLEAR_PROGRESS);
        context.startService(intent);
    }

    // endregion

    // region — Service lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationUtils.createNotificationChannel(
                this,
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.background_media_prep_notification_channel_name),
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
        } else if (ACTION_UPDATE_PROGRESS.equals(action)) {
            int loaded = intent.getIntExtra(EXTRA_LOADED, 0);
            int total = intent.getIntExtra(EXTRA_TOTAL, 0);
            String folder = intent.getStringExtra(EXTRA_FOLDER_DISPLAY);
            int cacheLoaded = intent.getIntExtra(EXTRA_CACHE_LOADED, 0);
            int cacheTotal = intent.getIntExtra(EXTRA_CACHE_TOTAL, 0);
            handleUpdateProgress(folder, loaded, total, cacheLoaded, cacheTotal);
        } else if (ACTION_CLEAR_PROGRESS.equals(action)) {
            handleClearProgress();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ThumbnailServerManager.getInstance().getState().removeObserver(stateObserver);
        observerRegistered = false;
        serverEverStarted = false;
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(false);
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(false);
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

        String folderExtra = intent.getStringExtra(EXTRA_FOLDER_DISPLAY);
        if (folderExtra != null && !folderExtra.isEmpty()) {
            folderDisplayPath = folderExtra;
        } else {
            folderDisplayPath = "//" + remote.getName();
        }

        lastThumbLoaded = 0;
        lastThumbTotal = 0;
        lastCacheLoaded = 0;
        lastCacheTotal = 0;
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(false);
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(false);

        startForegroundWithPlaceholder();
        ThumbnailServerManager manager = ThumbnailServerManager.getInstance();
        if (!observerRegistered) {
            manager.getState().observeForever(stateObserver);
            observerRegistered = true;
        }
        manager.start(this, remote, port, auth);
        applyMediaPrepForegroundState();
    }

    private void handleStop() {
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(false);
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(false);
        ThumbnailServerManager.getInstance().stop();
        // stateObserver will see STOPPED and call stopSelf(); but call it directly too
        // in case the manager was already stopped.
        stopForegroundCompat();
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        stopSelf();
    }

    private void handleUpdateProgress(@Nullable String folderFromIntent, int loaded, int total,
            int cacheLoaded, int cacheTotal) {
        if (notificationManager == null) {
            return;
        }
        if (folderFromIntent != null && !folderFromIntent.isEmpty()) {
            folderDisplayPath = folderFromIntent;
        }
        lastThumbLoaded = loaded;
        lastThumbTotal = total;
        lastCacheLoaded = cacheLoaded;
        lastCacheTotal = cacheTotal;

        boolean explorerBatch = total > 0 && loaded < total;
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(explorerBatch);
        boolean cacheWork = cacheTotal > 0 && cacheLoaded < cacheTotal;
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(cacheWork);

        applyMediaPrepForegroundState();
    }

    private void handleClearProgress() {
        if (notificationManager == null) {
            return;
        }
        lastThumbLoaded = 0;
        lastThumbTotal = 0;
        BackgroundMediaPrepWorkTracker.setExplorerThumbnailBatchInProgress(false);
        boolean cacheWork = lastCacheTotal > 0 && lastCacheLoaded < lastCacheTotal;
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(cacheWork);
        applyMediaPrepForegroundState();
    }

    // endregion

    // region — Notification

    /**
     * Satisfies the post-{@code startForegroundService} deadline; removed immediately when
     * {@link BackgroundMediaPrepWorkTracker#hasActiveWork()} is false.
     */
    private void startForegroundWithPlaceholder() {
        Notification notification = buildMinimalForegroundPlaceholder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildMinimalForegroundPlaceholder() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streaming)
                .setContentTitle(getString(R.string.background_media_prep_notification_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void applyMediaPrepForegroundState() {
        if (notificationManager == null) {
            return;
        }
        if (BackgroundMediaPrepWorkTracker.hasActiveWork()) {
            boolean showThumbProgress = lastThumbTotal > 0 && lastThumbLoaded < lastThumbTotal;
            Notification notification = buildMediaPrepNotification(
                    lastThumbLoaded, lastThumbTotal, lastCacheLoaded, lastCacheTotal, showThumbProgress);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            stopForegroundCompat();
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    /**
     * Foreground notification copy per {@code Roadmap/feature.md}: title {@code Background media prep},
     * folder line, optional Thumbnail and Cache lines when that work type is active.
     *
     * @param showThumbnailProgress when true and {@code thumbnailTotal > 0}, shows thumbnail counts and progress bar
     */
    private Notification buildMediaPrepNotification(int thumbnailLoaded, int thumbnailTotal,
            int cacheLoaded, int cacheTotal, boolean showThumbnailProgress) {
        String folderLine = getString(R.string.notification_media_prep_folder_line, folderDisplayPath);
        StringBuilder bigText = new StringBuilder(folderLine);
        boolean thumbActive = thumbnailTotal > 0;
        boolean cacheActive = cacheTotal > 0;
        if (thumbActive) {
            bigText.append('\n');
            bigText.append(getString(R.string.notification_media_prep_thumbnail_line,
                    thumbnailLoaded, thumbnailTotal));
        }
        if (cacheActive) {
            bigText.append('\n');
            bigText.append(getString(R.string.notification_media_prep_cache_line, cacheLoaded, cacheTotal));
        }
        String expanded = bigText.toString();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streaming)
                .setContentTitle(getString(R.string.background_media_prep_notification_title))
                .setContentText(folderLine)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true);
        if (showThumbnailProgress && thumbActive) {
            b.setProgress(thumbnailTotal, thumbnailLoaded, false);
        } else if (cacheActive && !thumbActive) {
            b.setProgress(cacheTotal, cacheLoaded, false);
        } else {
            b.setProgress(0, 0, false);
        }
        return b.build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    // endregion
}
