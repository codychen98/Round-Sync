package ca.pkay.rcloneexplorer.Glide;

/**
 * Exponential backoff for policy-extended thumbnail Glide retries (capped).
 */
public final class ThumbnailExtendedRetryDelays {

    private static final long BASE_MS = 500L;
    private static final long MAX_DELAY_MS = 45_000L;
    /** Prevents overflow when shifting; plateaus at {@link #MAX_DELAY_MS}. */
    private static final int MAX_SHIFT = 16;

    private ThumbnailExtendedRetryDelays() {
    }

    /**
     * @param attemptIndexZeroBased number of prior failures for this listener (0 = first retry delay)
     */
    public static long delayMsForAttempt(int attemptIndexZeroBased) {
        int idx = Math.max(0, attemptIndexZeroBased);
        int shift = Math.min(idx, MAX_SHIFT);
        long raw = BASE_MS << shift;
        return Math.min(MAX_DELAY_MS, raw);
    }
}
