package ca.pkay.rcloneexplorer.Glide;

/**
 * Seek positions for video thumbnail extraction when streaming over HTTP or from a local prefix file.
 */
final class VideoThumbnailSeekProbe {

    private VideoThumbnailSeekProbe() {
    }

    /**
     * Ordered Exo seek targets in milliseconds. Early positions (0s, 2s, 5s) come before deeper seeks
     * so remote MKVs can render before buffering a quarter of the file.
     */
    static long[] exoSeekAttemptsMs(long effectiveDurationMs, int reloadEpoch) {
        long tenthMs = effectiveDurationMs > 0
                ? Math.max(1L, effectiveDurationMs / 10)
                : 10_000L;
        long quarterMs = effectiveDurationMs > 0
                ? Math.max(1L, effectiveDurationMs / 4)
                : 30_000L;
        long[] base = new long[] {
                0L,
                2_000L,
                5_000L,
                10_000L,
                tenthMs,
                quarterMs,
        };
        if (reloadEpoch <= 0) {
            return base;
        }
        int start = reloadEpoch % base.length;
        long[] rotated = new long[base.length];
        for (int i = 0; i < base.length; i++) {
            rotated[i] = base[(start + i) % base.length];
        }
        return rotated;
    }
}
