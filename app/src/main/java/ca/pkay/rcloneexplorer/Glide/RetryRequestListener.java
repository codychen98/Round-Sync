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
    private int retryCount = 0;
    private Runnable pendingRunnable = null;

    public RetryRequestListener(
            @NonNull ThumbnailServerManager serverManager,
            @NonNull RetryLoadCallback loadCallback,
            @Nullable Context appContextForDiag,
            @NonNull String debugLoadKey) {
        this.serverManager = serverManager;
        this.loadCallback = loadCallback;
        this.appContext = appContextForDiag != null ? appContextForDiag.getApplicationContext() : null;
        this.debugLoadKey = debugLoadKey;
    }

    @Override
    public boolean onLoadFailed(
            @Nullable GlideException e,
            Object model,
            @NonNull Target<Drawable> target,
            boolean isFirstResource) {
        if (retryCount >= MAX_RETRIES) {
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
            return false; // let Glide show the error drawable
        }
        ThumbnailServerManager.ServerState state = serverManager.getState().getValue();
        long delay = RETRY_DELAYS_MS[retryCount];
        retryCount++;
        if (state != ThumbnailServerManager.ServerState.READY) {
            FLog.d(TAG, "Server state " + state + " (not READY); scheduling retry "
                    + retryCount + "/" + MAX_RETRIES + " after " + delay + "ms");
        } else {
            FLog.w(TAG, "Scheduling retry " + retryCount + "/" + MAX_RETRIES + " after " + delay + "ms");
        }
        pendingRunnable = () -> loadCallback.load(this);
        HANDLER.postDelayed(pendingRunnable, delay);
        return true; // suppress error drawable while retry is pending
    }

    @Override
    public boolean onResourceReady(
            @NonNull Drawable resource,
            @NonNull Object model,
            Target<Drawable> target,
            @NonNull DataSource dataSource,
            boolean isFirstResource) {
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
