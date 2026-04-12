package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SyncLog;

import java.util.List;

public class RetryRequestListener implements RequestListener<Drawable> {

    /**
     * Monotonic epoch from the host fragment (port/auth or serve generation); retries scheduled
     * under one epoch must not run after the epoch advances.
     */
    @FunctionalInterface
    public interface ThumbnailRetryEpochSource {
        int getEpoch();
    }

    /** When policy-extended retries are enabled, returns whether another delayed retry may be scheduled. */
    @FunctionalInterface
    public interface ThumbnailExtendedRetryScheduleGate {
        boolean allowSchedule();
    }

    /**
     * Callback invoked to re-issue a Glide load with the same listener attached,
     * allowing the listener to track retries across attempts.
     */
    public interface RetryLoadCallback {
        void load(RequestListener<Drawable> listener);
    }

    private static final String TAG = "RetryRequestListener";
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {500L, 1000L, 2000L};
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private final ThumbnailServerManager serverManager;
    private final RetryLoadCallback loadCallback;
    @Nullable
    private final Context appContext;
    @NonNull
    private final String debugLoadKey;
    @NonNull
    private final ThumbnailRetryEpochSource epochSource;
    private final boolean policyExtendedRetries;
    @Nullable
    private final ThumbnailExtendedRetryScheduleGate extendedScheduleGate;
    private int retryCount = 0;
    private Runnable pendingRunnable = null;

    public RetryRequestListener(
            @NonNull ThumbnailServerManager serverManager,
            @NonNull RetryLoadCallback loadCallback,
            @Nullable Context appContextForDiag,
            @NonNull String debugLoadKey,
            @NonNull ThumbnailRetryEpochSource epochSource,
            boolean policyExtendedRetries,
            @Nullable ThumbnailExtendedRetryScheduleGate extendedScheduleGate) {
        this.serverManager = serverManager;
        this.loadCallback = loadCallback;
        this.appContext = appContextForDiag != null ? appContextForDiag.getApplicationContext() : null;
        this.debugLoadKey = debugLoadKey;
        this.epochSource = epochSource;
        this.policyExtendedRetries = policyExtendedRetries;
        this.extendedScheduleGate = extendedScheduleGate;
    }

    @Override
    public boolean onLoadFailed(
            @Nullable GlideException e,
            Object model,
            @NonNull Target<Drawable> target,
            boolean isFirstResource) {
        if (appContext != null && debugLoadKey.endsWith("|vid")) {
            boolean willRetry = policyExtendedRetries
                    ? extendedScheduleGate != null && extendedScheduleGate.allowSchedule()
                    : retryCount < MAX_RETRIES;
            VideoThumbnailFetcher.logThumbPipe(
                    appContext,
                    "glideLoadFailed",
                    "key=" + debugLoadKey
                            + " attempt=" + (retryCount + 1)
                            + " willRetry=" + willRetry
                            + " mgrState=" + serverManager.getSyncState()
                            + " serveGen=" + serverManager.getServeGeneration()
                            + " causes=" + formatGlideRootCauses(e));
        }
        if (!policyExtendedRetries) {
            if (retryCount >= MAX_RETRIES) {
                logRetriesExhausted(e);
                return false;
            }
        } else {
            if (extendedScheduleGate == null || !extendedScheduleGate.allowSchedule()) {
                FLog.d(TAG, "Policy extended retry not scheduled (host inactive or server not READY)");
                return false;
            }
        }

        ThumbnailServerManager.ServerState state = serverManager.getState().getValue();
        long delay;
        if (policyExtendedRetries) {
            delay = ThumbnailExtendedRetryDelays.delayMsForAttempt(retryCount);
            retryCount++;
            FLog.d(TAG, "Scheduling policy-extended retry " + retryCount + " after " + delay + "ms"
                    + " serverState=" + state);
        } else {
            delay = RETRY_DELAYS_MS[retryCount];
            retryCount++;
            if (state != ThumbnailServerManager.ServerState.READY) {
                FLog.d(TAG, "Server state " + state + " (not READY); scheduling retry "
                        + retryCount + "/" + MAX_RETRIES + " after " + delay + "ms");
            } else {
                FLog.w(TAG, "Scheduling retry " + retryCount + "/" + MAX_RETRIES + " after " + delay + "ms");
            }
        }
        final int epochWhenScheduled = epochSource.getEpoch();
        pendingRunnable = () -> {
            if (ThumbnailRetryEpochs.isStale(epochWhenScheduled, epochSource.getEpoch())) {
                pendingRunnable = null;
                return;
            }
            if (policyExtendedRetries
                    && (extendedScheduleGate == null || !extendedScheduleGate.allowSchedule())) {
                pendingRunnable = null;
                return;
            }
            loadCallback.load(this);
        };
        HANDLER.postDelayed(pendingRunnable, delay);
        return true; // suppress error drawable while retry is pending
    }

    private void logRetriesExhausted(@Nullable GlideException e) {
        FLog.w(TAG, "Max retries reached, showing error drawable");
        if (appContext != null) {
            ThumbnailServerManager.ServerState live = serverManager.getState().getValue();
            ThumbnailServerManager.ServerState sync = serverManager.getSyncState();
            SyncLog.error(appContext, "VidThumbDbg",
                    "event=glideRetriesExhausted key=" + debugLoadKey
                            + " mgrStateLive=" + live
                            + " mgrStateSync=" + sync
                            + " serveGen=" + serverManager.getServeGeneration()
                            + " causes=" + formatGlideRootCauses(e));
        }
    }

    @Override
    public boolean onResourceReady(
            @NonNull Drawable resource,
            @NonNull Object model,
            Target<Drawable> target,
            @NonNull DataSource dataSource,
            boolean isFirstResource) {
        if (appContext != null && debugLoadKey.endsWith("|vid")) {
            VideoThumbnailFetcher.logThumbPipe(
                    appContext,
                    "glideResourceReady",
                    "key=" + debugLoadKey
                            + " dataSource=" + dataSource
                            + " mgrState=" + serverManager.getSyncState()
                            + " serveGen=" + serverManager.getServeGeneration());
        }
        if (policyExtendedRetries) {
            retryCount = 0;
        }
        return false; // let Glide handle success normally
    }

    public void cancel() {
        if (pendingRunnable != null) {
            HANDLER.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
    }

    @NonNull
    private static String formatGlideRootCauses(@Nullable GlideException e) {
        if (e == null) {
            return "(no GlideException)";
        }
        StringBuilder sb = new StringBuilder();
        List<Throwable> roots = e.getRootCauses();
        for (int i = 0; i < roots.size(); i++) {
            Throwable t = roots.get(i);
            sb.append('[').append(i).append(']')
                    .append(t.getClass().getSimpleName())
                    .append(": ")
                    .append(t.getMessage());
            if (i + 1 < roots.size()) {
                sb.append(" ; ");
            }
        }
        if (sb.length() == 0) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return sb.toString();
    }
}
