package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;

/**
 * Aligns sparse MKV cluster byte-range downloads to Matroska {@code Cluster} element boundaries
 * so Exo can demux sample data at cue-referenced positions.
 */
final class VideoMkvClusterAlign {

    private static final byte CLUSTER_ID_0 = (byte) 0x1F;
    private static final byte CLUSTER_ID_1 = (byte) 0x43;
    private static final byte CLUSTER_ID_2 = (byte) 0xB6;
    private static final byte CLUSTER_ID_3 = (byte) 0x75;

    private VideoMkvClusterAlign() {
    }

    /**
     * @return absolute file offset of the last {@code Cluster} element in {@code probe} at or
     *         before {@code candidatePos}, or {@code candidatePos} when none is found
     */
    static long alignClusterStart(
            @NonNull byte[] probe,
            long probeFileStart,
            long candidatePos) {
        long lastCluster = findLastClusterOffsetInProbe(probe, probeFileStart);
        if (lastCluster >= 0L && lastCluster <= candidatePos) {
            return lastCluster;
        }
        long firstCluster = findFirstClusterOffsetInProbe(probe, probeFileStart);
        if (firstCluster >= 0L && firstCluster >= candidatePos) {
            return firstCluster;
        }
        return candidatePos;
    }

    /**
     * @return absolute file offset of the first Cluster marker in the probe slice, or {@code -1}
     */
    static long findFirstClusterOffsetInProbe(@NonNull byte[] probe, long probeFileStart) {
        int limit = probe.length - 3;
        for (int i = 0; i < limit; i++) {
            if (isClusterId(probe, i)) {
                return probeFileStart + i;
            }
        }
        return -1L;
    }

    /**
     * @return absolute file offset of the last Cluster marker in the probe slice, or {@code -1}
     */
    static long findLastClusterOffsetInProbe(@NonNull byte[] probe, long probeFileStart) {
        long last = -1L;
        int limit = probe.length - 3;
        for (int i = 0; i < limit; i++) {
            if (isClusterId(probe, i)) {
                last = probeFileStart + i;
            }
        }
        return last;
    }

    private static boolean isClusterId(@NonNull byte[] data, int offset) {
        return data[offset] == CLUSTER_ID_0
                && data[offset + 1] == CLUSTER_ID_1
                && data[offset + 2] == CLUSTER_ID_2
                && data[offset + 3] == CLUSTER_ID_3;
    }
}
