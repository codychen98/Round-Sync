package ca.pkay.rcloneexplorer.Glide;

/**
 * Pure helpers for thumbnail retry scheduling vs current URL epoch (serve / param generation).
 */
public final class ThumbnailRetryEpochs {

    private ThumbnailRetryEpochs() {
    }

    public static boolean isStale(int epochWhenScheduled, int epochNow) {
        return epochWhenScheduled != epochNow;
    }
}
