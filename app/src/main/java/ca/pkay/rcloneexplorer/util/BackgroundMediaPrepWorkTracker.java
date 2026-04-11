package ca.pkay.rcloneexplorer.util;

import androidx.annotation.VisibleForTesting;

/**
 * Aggregates whether background media preparation should show a user-visible
 * foreground notification. Phase E prefetch will use {@link #incrementThumbnailPrefetchWork()} /
 * {@link #decrementThumbnailPrefetchWork()}; Phase F will drive {@link #setCacheWorkInProgress(boolean)}.
 */
public final class BackgroundMediaPrepWorkTracker {

    private static final Object LOCK = new Object();

    private static boolean explorerThumbnailBatchInProgress;
    private static int thumbnailPrefetchWorkRefCount;
    private static boolean cacheWorkInProgress;

    private BackgroundMediaPrepWorkTracker() {}

    public static void setExplorerThumbnailBatchInProgress(boolean inProgress) {
        synchronized (LOCK) {
            explorerThumbnailBatchInProgress = inProgress;
        }
    }

    public static void incrementThumbnailPrefetchWork() {
        synchronized (LOCK) {
            thumbnailPrefetchWorkRefCount++;
        }
    }

    public static void decrementThumbnailPrefetchWork() {
        synchronized (LOCK) {
            if (thumbnailPrefetchWorkRefCount > 0) {
                thumbnailPrefetchWorkRefCount--;
            }
        }
    }

    public static void setCacheWorkInProgress(boolean inProgress) {
        synchronized (LOCK) {
            cacheWorkInProgress = inProgress;
        }
    }

    public static boolean hasActiveWork() {
        synchronized (LOCK) {
            return explorerThumbnailBatchInProgress
                    || thumbnailPrefetchWorkRefCount > 0
                    || cacheWorkInProgress;
        }
    }

    @VisibleForTesting
    public static void resetForTests() {
        synchronized (LOCK) {
            explorerThumbnailBatchInProgress = false;
            thumbnailPrefetchWorkRefCount = 0;
            cacheWorkInProgress = false;
        }
    }
}
