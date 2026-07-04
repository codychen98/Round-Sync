package ca.pkay.rcloneexplorer.Glide;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * Seek positions for video thumbnail extraction when streaming over HTTP or from a local prefix file.
 */
final class VideoThumbnailSeekProbe {

    /**
     * Positions within this window class of the last successful source position are considered "the same
     * frame" for reload purposes (CLOSEST_SYNC / keyframe snapping can map nearby requests to one
     * keyframe).
     */
    static final long SAME_FRAME_TOLERANCE_MS = 1_500L;

    /** Percent-of-duration grid slots when the fixed probe pool is exhausted. */
    private static final int EXPANDED_GRID_SLOTS = 24;

    private VideoThumbnailSeekProbe() {
    }

    /**
     * Ordered Exo seek targets in milliseconds. Early positions (0s, 2s, 5s) come before deeper seeks
     * so remote MKVs can render before buffering a quarter of the file.
     */
    static long[] exoSeekAttemptsMs(long effectiveDurationMs, int reloadEpoch) {
        return exoSeekAttemptsMs(
                effectiveDurationMs, reloadEpoch, Collections.<Long>emptySet());
    }

    /**
     * Same as {@link #exoSeekAttemptsMs(long, int)} but on reload deprioritizes positions near
     * {@code lastSourcePositionMs} (the position behind the current thumbnail) so the reload does
     * not re-capture the identical frame (Step 15B).
     */
    static long[] exoSeekAttemptsMs(
            long effectiveDurationMs, int reloadEpoch, long lastSourcePositionMs) {
        if (lastSourcePositionMs < 0L) {
            return exoSeekAttemptsMs(effectiveDurationMs, reloadEpoch, Collections.<Long>emptySet());
        }
        return exoSeekAttemptsMs(
                effectiveDurationMs, reloadEpoch, Collections.singleton(lastSourcePositionMs));
    }

    /**
     * On reload skips positions near any entry in {@code usedSourcePositionsMs}. When the fixed pool
     * is exhausted, expands to a finer duration grid so each reload can still find a fresh frame.
     */
    static long[] exoSeekAttemptsMs(
            long effectiveDurationMs,
            int reloadEpoch,
            Collection<Long> usedSourcePositionsMs) {
        long[] base = buildExoBaseAttemptsMs(effectiveDurationMs);
        if (reloadEpoch <= 0
                && (usedSourcePositionsMs == null || usedSourcePositionsMs.isEmpty())) {
            return base;
        }
        int start = reloadEpoch <= 0 ? 0 : reloadEpoch % base.length;
        long[] rotated = rotateMs(base, start);
        long[] fresh = excludeUsedSourceMs(rotated, usedSourcePositionsMs);
        if (fresh.length > 0) {
            return fresh;
        }
        long[] expanded = buildExpandedSeekCandidatesMs(
                effectiveDurationMs, reloadEpoch, usedSourcePositionsMs);
        if (expanded.length > 0) {
            return expanded;
        }
        return buildExpandedSeekCandidatesMs(
                effectiveDurationMs,
                reloadEpoch + EXPANDED_GRID_SLOTS / 2,
                Collections.emptySet());
    }

    /**
     * MMR reload probe times (microseconds). Skips used source positions and expands when needed.
     */
    static long[] mmrReloadProbeTimesUs(
            long durationMs,
            long durationUs,
            int reloadEpoch,
            Collection<Long> usedSourcePositionsMs) {
        long[] fractionsUs = buildMmrReloadFractionsUs(durationMs, durationUs);
        int start = reloadEpoch % fractionsUs.length;
        long[] rotated = new long[fractionsUs.length];
        for (int i = 0; i < fractionsUs.length; i++) {
            rotated[i] = fractionsUs[(start + i) % fractionsUs.length];
        }
        long[] fresh = excludeUsedSourceUs(rotated, usedSourcePositionsMs);
        if (fresh.length > 0) {
            return fresh;
        }
        long[] expandedMs = buildExpandedSeekCandidatesMs(
                durationMs, reloadEpoch, usedSourcePositionsMs);
        if (expandedMs.length == 0) {
            expandedMs = buildExpandedSeekCandidatesMs(
                    durationMs,
                    reloadEpoch + EXPANDED_GRID_SLOTS / 2,
                    Collections.emptySet());
        }
        long[] expandedUs = new long[expandedMs.length];
        for (int i = 0; i < expandedMs.length; i++) {
            expandedUs[i] = clampTimeUs(expandedMs[i] * 1_000L, durationUs);
        }
        return expandedUs;
    }

    private static long[] buildExoBaseAttemptsMs(long effectiveDurationMs) {
        long tenthMs = effectiveDurationMs > 0
                ? Math.max(1L, effectiveDurationMs / 10)
                : 10_000L;
        long quarterMs = effectiveDurationMs > 0
                ? Math.max(1L, effectiveDurationMs / 4)
                : 30_000L;
        return new long[] {
                0L,
                2_000L,
                5_000L,
                10_000L,
                tenthMs,
                quarterMs,
        };
    }

    private static long[] buildMmrReloadFractionsUs(long durationMs, long durationUs) {
        if (durationMs > 0) {
            return new long[] {
                    0L,
                    durationUs / 10,
                    durationUs / 4,
                    durationUs / 2,
            };
        }
        return new long[] {
                0L,
                1_000_000L,
                2_500_000L,
                5_000_000L,
        };
    }

    private static long[] rotateMs(long[] base, int start) {
        long[] rotated = new long[base.length];
        for (int i = 0; i < base.length; i++) {
            rotated[i] = base[(start + i) % base.length];
        }
        return rotated;
    }

    /**
     * Finer seek grid used after the fixed probe pool is exhausted. Rotated by reload epoch so
     * successive reloads pick different unused slots.
     */
    static long[] buildExpandedSeekCandidatesMs(
            long durationMs,
            int reloadEpoch,
            Collection<Long> usedSourcePositionsMs) {
        LinkedHashSet<Long> candidates = new LinkedHashSet<>();
        if (durationMs > 0) {
            int start = Math.floorMod(reloadEpoch, EXPANDED_GRID_SLOTS);
            for (int i = 0; i < EXPANDED_GRID_SLOTS; i++) {
                int slot = (start + i) % EXPANDED_GRID_SLOTS;
                long pct = 3L + slot * 4L;
                if (pct > 97L) {
                    pct = 97L;
                }
                long ms = Math.max(1L, durationMs * pct / 100L);
                if (!matchesAnyUsedSourceMs(ms, usedSourcePositionsMs)) {
                    candidates.add(ms);
                }
            }
        }
        long[] extra = {
                3_000L, 7_000L, 12_000L, 18_000L, 22_000L, 28_000L,
                35_000L, 42_000L, 50_000L, 65_000L, 80_000L, 90_000L,
        };
        for (long ms : extra) {
            if (durationMs <= 0L || ms < durationMs) {
                if (!matchesAnyUsedSourceMs(ms, usedSourcePositionsMs)) {
                    candidates.add(ms);
                }
            }
        }
        return longCollectionToArray(candidates);
    }

    /**
     * Moves entries within {@link #SAME_FRAME_TOLERANCE_MS} of {@code lastSourcePositionMs} to the
     * end of the array, preserving relative order otherwise. Never removes attempts, so extraction
     * still succeeds when the last-used position is the only one that works.
     */
    static long[] deprioritizeLastSourceMs(long[] attemptsMs, long lastSourcePositionMs) {
        if (lastSourcePositionMs < 0L) {
            return attemptsMs;
        }
        return deprioritizeUsedSourceMs(attemptsMs, Collections.singleton(lastSourcePositionMs));
    }

    /**
     * Moves entries within tolerance of any used source position to the end. When every attempt
     * matches a used position, returns the original order so extraction can still succeed.
     *
     * @deprecated Prefer {@link #excludeUsedSourceMs} which removes used positions entirely.
     */
    static long[] deprioritizeUsedSourceMs(
            long[] attemptsMs,
            Collection<Long> usedSourcePositionsMs) {
        if (usedSourcePositionsMs == null
                || usedSourcePositionsMs.isEmpty()
                || attemptsMs.length <= 1) {
            return attemptsMs;
        }
        int freshCount = 0;
        for (long attempt : attemptsMs) {
            if (!matchesAnyUsedSourceMs(attempt, usedSourcePositionsMs)) {
                freshCount++;
            }
        }
        if (freshCount == 0) {
            return attemptsMs;
        }
        long[] out = new long[attemptsMs.length];
        int head = 0;
        for (long attempt : attemptsMs) {
            if (!matchesAnyUsedSourceMs(attempt, usedSourcePositionsMs)) {
                out[head++] = attempt;
            }
        }
        if (head == attemptsMs.length) {
            return attemptsMs;
        }
        int tail = head;
        for (long attempt : attemptsMs) {
            if (matchesAnyUsedSourceMs(attempt, usedSourcePositionsMs)) {
                out[tail++] = attempt;
            }
        }
        return out;
    }

    /** Drops seek targets that would reproduce a frame near an already-used source position. */
    static long[] excludeUsedSourceMs(
            long[] attemptsMs,
            Collection<Long> usedSourcePositionsMs) {
        if (usedSourcePositionsMs == null || usedSourcePositionsMs.isEmpty()) {
            return attemptsMs;
        }
        ArrayList<Long> fresh = new ArrayList<>(attemptsMs.length);
        for (long attempt : attemptsMs) {
            if (!matchesAnyUsedSourceMs(attempt, usedSourcePositionsMs)) {
                fresh.add(attempt);
            }
        }
        return longCollectionToArray(fresh);
    }

    /** Microsecond variant for MediaMetadataRetriever probe lists. */
    static long[] deprioritizeLastSourceUs(long[] attemptsUs, long lastSourcePositionMs) {
        if (lastSourcePositionMs < 0L) {
            return attemptsUs;
        }
        return deprioritizeUsedSourceUs(attemptsUs, Collections.singleton(lastSourcePositionMs));
    }

    static long[] deprioritizeUsedSourceUs(
            long[] attemptsUs,
            Collection<Long> usedSourcePositionsMs) {
        if (usedSourcePositionsMs == null
                || usedSourcePositionsMs.isEmpty()
                || attemptsUs.length <= 1) {
            return attemptsUs;
        }
        long[] attemptsMs = new long[attemptsUs.length];
        for (int i = 0; i < attemptsUs.length; i++) {
            attemptsMs[i] = attemptsUs[i] / 1_000L;
        }
        long[] reorderedMs = deprioritizeUsedSourceMs(attemptsMs, usedSourcePositionsMs);
        if (reorderedMs == attemptsMs) {
            return attemptsUs;
        }
        return remapUsFromMsOrder(attemptsUs, reorderedMs);
    }

    static long[] excludeUsedSourceUs(
            long[] attemptsUs,
            Collection<Long> usedSourcePositionsMs) {
        if (usedSourcePositionsMs == null || usedSourcePositionsMs.isEmpty()) {
            return attemptsUs;
        }
        ArrayList<Long> fresh = new ArrayList<>(attemptsUs.length);
        for (long attemptUs : attemptsUs) {
            if (!matchesAnyUsedSourceMs(attemptUs / 1_000L, usedSourcePositionsMs)) {
                fresh.add(attemptUs);
            }
        }
        long[] out = new long[fresh.size()];
        for (int i = 0; i < fresh.size(); i++) {
            out[i] = fresh.get(i);
        }
        return out;
    }

    private static long[] remapUsFromMsOrder(long[] attemptsUs, long[] orderMs) {
        long[] out = new long[orderMs.length];
        boolean[] used = new boolean[attemptsUs.length];
        for (int i = 0; i < orderMs.length; i++) {
            for (int j = 0; j < attemptsUs.length; j++) {
                if (!used[j] && attemptsUs[j] / 1_000L == orderMs[i]) {
                    out[i] = attemptsUs[j];
                    used[j] = true;
                    break;
                }
            }
        }
        return out;
    }

    private static long clampTimeUs(long timeUs, long durationUs) {
        if (timeUs < 0L) {
            return 0L;
        }
        if (durationUs > 0L && timeUs > durationUs) {
            return durationUs;
        }
        return timeUs;
    }

    private static long[] longCollectionToArray(Collection<Long> values) {
        long[] out = new long[values.size()];
        int i = 0;
        for (Long value : values) {
            out[i++] = value;
        }
        return out;
    }

    static boolean isSameFrameMs(long candidateMs, long lastSourcePositionMs) {
        return lastSourcePositionMs >= 0L
                && Math.abs(candidateMs - lastSourcePositionMs) <= SAME_FRAME_TOLERANCE_MS;
    }

    static boolean matchesAnyUsedSourceMs(long candidateMs, Collection<Long> usedSourcePositionsMs) {
        if (usedSourcePositionsMs == null || usedSourcePositionsMs.isEmpty()) {
            return false;
        }
        for (Long used : usedSourcePositionsMs) {
            if (used != null && isSameFrameMs(candidateMs, used)) {
                return true;
            }
        }
        return false;
    }
}
