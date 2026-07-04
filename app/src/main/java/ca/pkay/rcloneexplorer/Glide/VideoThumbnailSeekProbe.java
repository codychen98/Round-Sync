package ca.pkay.rcloneexplorer.Glide;

/**
 * Seek positions for video thumbnail extraction when streaming over HTTP or from a local prefix file.
 */
final class VideoThumbnailSeekProbe {

    /**
     * Positions within this window of the last successful source position are considered "the same
     * frame" for reload purposes (CLOSEST_SYNC / keyframe snapping can map nearby requests to one
     * keyframe) and are moved to the end of the attempt order.
     */
    static final long SAME_FRAME_TOLERANCE_MS = 1_500L;

    private VideoThumbnailSeekProbe() {
    }

    /**
     * Ordered Exo seek targets in milliseconds. Early positions (0s, 2s, 5s) come before deeper seeks
     * so remote MKVs can render before buffering a quarter of the file.
     */
    static long[] exoSeekAttemptsMs(long effectiveDurationMs, int reloadEpoch) {
        return exoSeekAttemptsMs(
                effectiveDurationMs, reloadEpoch, ThumbnailReloadEpoch.NO_SOURCE_POSITION);
    }

    /**
     * Same as {@link #exoSeekAttemptsMs(long, int)} but on reload deprioritizes positions near
     * {@code lastSourcePositionMs} (the position behind the current thumbnail) so the reload does
     * not re-capture the identical frame (Step 15B).
     */
    static long[] exoSeekAttemptsMs(
            long effectiveDurationMs, int reloadEpoch, long lastSourcePositionMs) {
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
        return deprioritizeLastSourceMs(rotated, lastSourcePositionMs);
    }

    /**
     * Moves entries within {@link #SAME_FRAME_TOLERANCE_MS} of {@code lastSourcePositionMs} to the
     * end of the array, preserving relative order otherwise. Never removes attempts, so extraction
     * still succeeds when the last-used position is the only one that works.
     */
    static long[] deprioritizeLastSourceMs(long[] attemptsMs, long lastSourcePositionMs) {
        if (lastSourcePositionMs < 0L || attemptsMs.length <= 1) {
            return attemptsMs;
        }
        long[] out = new long[attemptsMs.length];
        int head = 0;
        for (long attempt : attemptsMs) {
            if (!isSameFrameMs(attempt, lastSourcePositionMs)) {
                out[head++] = attempt;
            }
        }
        if (head == attemptsMs.length) {
            return attemptsMs;
        }
        int tail = head;
        for (long attempt : attemptsMs) {
            if (isSameFrameMs(attempt, lastSourcePositionMs)) {
                out[tail++] = attempt;
            }
        }
        return out;
    }

    /** Microsecond variant for MediaMetadataRetriever probe lists. */
    static long[] deprioritizeLastSourceUs(long[] attemptsUs, long lastSourcePositionMs) {
        if (lastSourcePositionMs < 0L || attemptsUs.length <= 1) {
            return attemptsUs;
        }
        long[] attemptsMs = new long[attemptsUs.length];
        for (int i = 0; i < attemptsUs.length; i++) {
            attemptsMs[i] = attemptsUs[i] / 1_000L;
        }
        long[] reorderedMs = deprioritizeLastSourceMs(attemptsMs, lastSourcePositionMs);
        if (reorderedMs == attemptsMs) {
            return attemptsUs;
        }
        long[] out = new long[attemptsUs.length];
        boolean[] used = new boolean[attemptsUs.length];
        for (int i = 0; i < reorderedMs.length; i++) {
            for (int j = 0; j < attemptsUs.length; j++) {
                if (!used[j] && attemptsUs[j] / 1_000L == reorderedMs[i]) {
                    out[i] = attemptsUs[j];
                    used[j] = true;
                    break;
                }
            }
        }
        return out;
    }

    static boolean isSameFrameMs(long candidateMs, long lastSourcePositionMs) {
        return lastSourcePositionMs >= 0L
                && Math.abs(candidateMs - lastSourcePositionMs) <= SAME_FRAME_TOLERANCE_MS;
    }
}
