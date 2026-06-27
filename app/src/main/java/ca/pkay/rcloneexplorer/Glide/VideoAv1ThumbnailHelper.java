package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Request;
import okhttp3.Response;

/**
 * AV1-in-MKV thumbnails: MMR cannot decode frames; head-only partial files lack Matroska Cues.
 * Downloads head + tail byte ranges and runs Exo on a sparse {@link VideoMkvSparseDataSource}.
 */
final class VideoAv1ThumbnailHelper {

    /** Hidden SDK key ({@code MediaMetadataRetriever.METADATA_KEY_VIDEO_CODEC_MIME_TYPE}). */
    private static final int METADATA_KEY_VIDEO_CODEC_MIME_TYPE = 40;
    private static final long DEFAULT_HEAD_BYTES = 48L * 1024L * 1024L;
    private static final long DEFAULT_TAIL_BYTES = 24L * 1024L * 1024L;
    private static final long DEFAULT_CLUSTER_BYTES = 64L * 1024L * 1024L;
    private static final long RELOAD_HEAD_BYTES = 64L * 1024L * 1024L;
    private static final long RELOAD_TAIL_BYTES = 32L * 1024L * 1024L;
    private static final long RELOAD_CLUSTER_BYTES = 96L * 1024L * 1024L;
    private static final long DEFAULT_MAX_EXPANDED_HEAD_BYTES = 160L * 1024L * 1024L;
    private static final long RELOAD_MAX_EXPANDED_HEAD_BYTES = 192L * 1024L * 1024L;
    private static final int MAX_CLUSTER_ATTEMPTS = 4;
    private static final int[] GAP_PERCENTILES = {25, 50, 75};
    private static final int[] GAP_CLUSTER_PROBE_PERCENTILES = {10, 25, 50};
    private static final long CLUSTER_ALIGN_PROBE_BACK_BYTES = 512L * 1024L;
    private static final long CLUSTER_ALIGN_PROBE_LENGTH_BYTES = 768L * 1024L;
    private static final long CLUSTER_POST_ALIGN_PREFIX_BYTES = 256L * 1024L;

    private VideoAv1ThumbnailHelper() {
    }

    static boolean isAv1Codec(@Nullable String codecMime) {
        if (codecMime == null || codecMime.isEmpty()) {
            return false;
        }
        String lower = codecMime.toLowerCase();
        return lower.contains("av01") || lower.contains("av1");
    }

    @Nullable
    static String readVideoCodecMime(@NonNull android.media.MediaMetadataRetriever mmr) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return mmr.extractMetadata(METADATA_KEY_VIDEO_CODEC_MIME_TYPE);
        }
        return null;
    }

    @Nullable
    static Bitmap tryGrabFromSparseHeadTail(
            @NonNull Context appContext,
            @NonNull String url,
            long fileSizeBytes,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            @Nullable String stablePath,
            boolean userReload) {
        if (fileSizeBytes <= 0L || cancellation.isCancelled()) {
            return null;
        }
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        long headCap = userReload ? RELOAD_HEAD_BYTES : DEFAULT_HEAD_BYTES;
        long tailCap = userReload ? RELOAD_TAIL_BYTES : DEFAULT_TAIL_BYTES;
        long headBytes = Math.min(headCap, fileSizeBytes);
        long tailBytes = Math.min(tailCap, Math.max(0L, fileSizeBytes - headBytes));
        if (tailBytes <= 0L) {
            tailBytes = Math.min(tailCap, fileSizeBytes);
            headBytes = Math.max(0L, fileSizeBytes - tailBytes);
        }
        File headFile = null;
        File tailFile = null;
        try {
            headFile = File.createTempFile("thumb_mkv_head_", ".bin", appContext.getCacheDir());
            tailFile = File.createTempFile("thumb_mkv_tail_", ".bin", appContext.getCacheDir());
            if (!downloadRange(url, headFile, 0L, headBytes)) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHeadFail",
                        "basename=" + base + " headBytes=" + headBytes);
                return null;
            }
            long tailRemoteStart = Math.max(0L, fileSizeBytes - tailBytes);
            if (!downloadRange(url, tailFile, tailRemoteStart, tailBytes)) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseTailFail",
                        "basename=" + base + " tailBytes=" + tailBytes);
                return null;
            }
            long actualHead = headFile.length();
            long actualTail = tailFile.length();
            long clusterCap = userReload ? RELOAD_CLUSTER_BYTES : DEFAULT_CLUSTER_BYTES;
            long maxExpandedHead = userReload ? RELOAD_MAX_EXPANDED_HEAD_BYTES : DEFAULT_MAX_EXPANDED_HEAD_BYTES;
            List<Long> clusterCandidates = findClusterDownloadCandidates(
                    url,
                    headFile,
                    tailFile,
                    fileSizeBytes,
                    actualHead,
                    tailRemoteStart);
            long firstCluster = discoverFirstClusterForHeadExpand(
                    appContext,
                    url,
                    base,
                    headFile,
                    tailFile,
                    fileSizeBytes,
                    actualHead,
                    tailRemoteStart,
                    clusterCandidates);
            HeadExpandResult expandedHead = maybeExpandHeadForFirstCluster(
                    appContext,
                    url,
                    base,
                    headFile,
                    actualHead,
                    tailRemoteStart,
                    firstCluster,
                    clusterCap,
                    maxExpandedHead);
            headFile = expandedHead.file;
            actualHead = expandedHead.bytes;
            if (expandedHead.expanded) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHeadExpand",
                        "basename=" + base
                        + " firstCluster=" + firstCluster
                        + " headBytes=" + actualHead
                        + " userReload=" + userReload);
            } else if (!clusterCandidates.isEmpty()) {
                long fromCandidates = earliestGapCluster(clusterCandidates, actualHead, tailRemoteStart);
                if (fromCandidates >= actualHead
                        && (firstCluster < 0L || firstCluster < actualHead)) {
                    firstCluster = fromCandidates;
                    expandedHead = maybeExpandHeadForFirstCluster(
                            appContext,
                            url,
                            base,
                            headFile,
                            actualHead,
                            tailRemoteStart,
                            firstCluster,
                            clusterCap,
                            maxExpandedHead);
                    headFile = expandedHead.file;
                    actualHead = expandedHead.bytes;
                    if (expandedHead.expanded) {
                        VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHeadExpand",
                                "basename=" + base
                                + " firstCluster=" + firstCluster
                                + " headBytes=" + actualHead
                                + " userReload=" + userReload
                                + " source=candidatesRetry");
                    }
                }
            }
            if (!expandedHead.expanded) {
                String skipReason = firstCluster < 0L
                        ? "noCluster"
                        : (firstCluster < actualHead ? "clusterInsideHead" : "expandDownloadFailed");
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHeadExpandSkip",
                        "basename=" + base
                        + " reason=" + skipReason
                        + " firstCluster=" + firstCluster
                        + " headBytes=" + actualHead);
            }
            long gapClusterForExpand = earliestGapCluster(clusterCandidates, actualHead, tailRemoteStart);
            if (firstCluster >= 0L
                    && firstCluster < actualHead
                    && gapClusterForExpand < actualHead) {
                Bitmap expandedOnly = trySparseExoWithCluster(
                        appContext,
                        url,
                        base,
                        fileSizeBytes,
                        durationMs,
                        cancellation,
                        stablePath,
                        userReload,
                        headFile,
                        actualHead,
                        tailFile,
                        tailRemoteStart,
                        actualTail,
                        -1L,
                        clusterCap,
                        0,
                        1);
                if (expandedOnly != null) {
                    return expandedOnly;
                }
            }
            if (clusterCandidates.isEmpty()) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterSkip",
                        "basename=" + base
                        + " clusterPos=-1"
                        + " headBytes=" + actualHead
                        + " tailStart=" + tailRemoteStart);
            }
            int attemptLimit = Math.min(clusterCandidates.size(), MAX_CLUSTER_ATTEMPTS);
            int attemptIndex = 0;
            for (int attempt = 0; attempt < attemptLimit; attempt++) {
                if (cancellation.isCancelled()) {
                    return null;
                }
                long clusterPos = clusterCandidates.get(attempt);
                if (clusterPos >= 0L && clusterPos < actualHead) {
                    continue;
                }
                Bitmap frame = trySparseExoWithCluster(
                        appContext,
                        url,
                        base,
                        fileSizeBytes,
                        durationMs,
                        cancellation,
                        stablePath,
                        userReload,
                        headFile,
                        actualHead,
                        tailFile,
                        tailRemoteStart,
                        actualTail,
                        clusterPos,
                        clusterCap,
                        attemptIndex,
                        clusterCandidates.size());
                attemptIndex++;
                if (frame != null) {
                    return frame;
                }
            }
            return null;
        } catch (Exception e) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "sparseExtractFail",
                    "basename=" + base + " what=" + e.getClass().getSimpleName());
            return null;
        } finally {
            deleteQuietly(headFile);
            deleteQuietly(tailFile);
        }
    }

    @Nullable
    private static Bitmap trySparseExoWithCluster(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull String base,
            long fileSizeBytes,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            @Nullable String stablePath,
            boolean userReload,
            @NonNull File headFile,
            long actualHead,
            @NonNull File tailFile,
            long tailRemoteStart,
            long actualTail,
            long clusterPos,
            long clusterCap,
            int attemptIndex,
            int candidateCount) {
        File clusterFile = null;
        long clusterRemoteStart = 0L;
        long actualCluster = 0L;
        boolean gapFallback = false;
        try {
            if (clusterPos >= actualHead && clusterPos < tailRemoteStart) {
                long alignedPos = alignClusterDownloadStart(
                        appContext,
                        url,
                        clusterPos,
                        actualHead,
                        tailRemoteStart);
                if (alignedPos != clusterPos) {
                    String alignKind = alignedPos < clusterPos ? "backward" : "forward";
                    VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterAlign",
                            "basename=" + base
                            + " rawStart=" + clusterPos
                            + " alignedStart=" + alignedPos
                            + " alignKind=" + alignKind
                            + " attempt=" + (attemptIndex + 1));
                    clusterPos = alignedPos;
                }
                long clusterLength = Math.min(clusterCap, tailRemoteStart - clusterPos);
                if (clusterLength > 0L) {
                    clusterFile = File.createTempFile("thumb_mkv_cluster_", ".bin", appContext.getCacheDir());
                    if (downloadRange(url, clusterFile, clusterPos, clusterLength)) {
                        clusterRemoteStart = clusterPos;
                        actualCluster = clusterFile.length();
                        ClusterTrimResult trimmed = trimClusterToFirstBoundary(
                                clusterFile,
                                clusterRemoteStart,
                                actualCluster);
                        clusterRemoteStart = trimmed.remoteStart;
                        actualCluster = trimmed.bytes;
                        gapFallback = !clusterPosFromCues(headFile, tailFile, clusterPos);
                        VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterOk",
                                "basename=" + base
                                + " clusterStart=" + clusterRemoteStart
                                + " clusterBytes=" + actualCluster
                                + " gapFallback=" + gapFallback
                                + " attempt=" + (attemptIndex + 1)
                                + " candidates=" + candidateCount);
                    } else {
                        deleteQuietly(clusterFile);
                        clusterFile = null;
                        VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterFail",
                                "basename=" + base
                                + " clusterStart=" + clusterPos
                                + " attempt=" + (attemptIndex + 1));
                        return null;
                    }
                }
            }
            VideoThumbnailFetcher.logThumbPipe(appContext, "sparseDownloadOk",
                    "basename=" + base
                    + " totalSize=" + fileSizeBytes
                    + " headBytes=" + actualHead
                    + " tailStart=" + tailRemoteStart
                    + " tailBytes=" + actualTail
                    + " clusterBytes=" + actualCluster
                    + " userReload=" + userReload
                    + " attempt=" + (attemptIndex + 1));
            VideoMkvSparseDataSource.HeadTailFiles files = new VideoMkvSparseDataSource.HeadTailFiles(
                    fileSizeBytes,
                    headFile,
                    actualHead,
                    clusterFile,
                    clusterRemoteStart,
                    actualCluster,
                    tailFile,
                    tailRemoteStart,
                    actualTail);
            Uri sparseUri = Uri.parse("sparse-mkv://" + base + "#" + attemptIndex);
            Bitmap frame = VideoThumbnailExoFallback.tryGrabFromDataSourceFactory(
                    appContext,
                    new VideoMkvSparseDataSource.Factory(files, sparseUri),
                    sparseUri.toString(),
                    durationMs,
                    cancellation,
                    true,
                    userReload,
                    stablePath);
            if (frame == null) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterRetry",
                        "basename=" + base
                        + " clusterStart=" + clusterRemoteStart
                        + " attempt=" + (attemptIndex + 1)
                        + " candidates=" + candidateCount);
            }
            return frame;
        } catch (Exception ignored) {
            return null;
        } finally {
            deleteQuietly(clusterFile);
        }
    }

    private static long alignClusterDownloadStart(
            @NonNull Context appContext,
            @NonNull String url,
            long candidatePos,
            long headBytes,
            long tailRemoteStart) {
        if (candidatePos < headBytes || candidatePos >= tailRemoteStart) {
            return candidatePos;
        }
        long probeStart = Math.max(headBytes, candidatePos - CLUSTER_ALIGN_PROBE_BACK_BYTES);
        long probeLength = Math.min(CLUSTER_ALIGN_PROBE_LENGTH_BYTES, tailRemoteStart - probeStart);
        if (probeLength <= 0L) {
            return candidatePos;
        }
        File probeFile = null;
        try {
            probeFile = File.createTempFile("thumb_mkv_cluster_probe_", ".bin", appContext.getCacheDir());
            if (!downloadRange(url, probeFile, probeStart, probeLength)) {
                return candidatePos;
            }
            byte[] probeBytes = readProbeBytes(probeFile);
            return VideoMkvClusterAlign.alignClusterStart(probeBytes, probeStart, candidatePos);
        } catch (Exception ignored) {
            return candidatePos;
        } finally {
            deleteQuietly(probeFile);
        }
    }

    @NonNull
    private static byte[] readProbeBytes(@NonNull File file) throws java.io.IOException {
        long length = file.length();
        if (length <= 0L || length > CLUSTER_ALIGN_PROBE_LENGTH_BYTES) {
            throw new java.io.IOException("cluster probe too large");
        }
        byte[] data = new byte[(int) length];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            return offset == data.length ? data : java.util.Arrays.copyOf(data, offset);
        }
    }

    private static boolean clusterPosFromCues(
            @NonNull File headFile,
            @NonNull File tailFile,
            long clusterPos) {
        try {
            for (long cuePos : VideoMkvCueParser.findAllClusterPositions(headFile, tailFile)) {
                if (cuePos == clusterPos) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static final class HeadExpandResult {
        @NonNull
        final File file;
        final long bytes;
        final boolean expanded;

        HeadExpandResult(@NonNull File file, long bytes, boolean expanded) {
            this.file = file;
            this.bytes = bytes;
            this.expanded = expanded;
        }
    }

    private static final class ClusterTrimResult {
        final long remoteStart;
        final long bytes;

        ClusterTrimResult(long remoteStart, long bytes) {
            this.remoteStart = remoteStart;
            this.bytes = bytes;
        }
    }

    /**
     * Target head prefix length when the first Matroska Cluster lies beyond the default head cap.
     */
    static long computeExpandedHeadEnd(
            long actualHead,
            long firstCluster,
            long clusterCap,
            long tailRemoteStart,
            long maxExpandedHeadBytes) {
        if (firstCluster < actualHead || firstCluster >= tailRemoteStart) {
            return actualHead;
        }
        long targetEnd = Math.min(firstCluster + clusterCap, tailRemoteStart);
        targetEnd = Math.min(targetEnd, maxExpandedHeadBytes);
        return targetEnd > actualHead ? targetEnd : actualHead;
    }

    /**
     * Earliest in-gap cluster for head expansion. Ignores Cluster IDs inside the downloaded
     * head prefix (e.g. attachments at ~400 KB) — only positions in the head/tail gap count.
     */
    static long discoverFirstClusterForHeadExpand(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull String base,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart,
            @NonNull List<Long> clusterCandidates) {
        StringBuilder sources = new StringBuilder();
        long fromGapScan = findEarliestGapClusterPosition(
                appContext,
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart);
        if (fromGapScan >= 0L) {
            sources.append("gapScan");
        }
        long fromCandidates = earliestGapCluster(clusterCandidates, headBytes, tailRemoteStart);
        if (fromCandidates >= 0L) {
            if (sources.length() > 0) {
                sources.append(",");
            }
            sources.append("candidates");
        }
        long skippedInHead = findEarliestClusterInHeadPrefix(headFile);
        long result = resolveHeadExpandCluster(headBytes, tailRemoteStart, fromGapScan, fromCandidates);
        VideoThumbnailFetcher.logThumbPipe(appContext, "firstClusterDiscovery",
                "basename=" + base
                + " sources=" + (sources.length() > 0 ? sources : "none")
                + " result=" + result
                + " headBytes=" + headBytes
                + (skippedInHead >= 0L ? " skippedInHead=" + skippedInHead : ""));
        return result;
    }

    /**
     * Picks the earliest cluster strictly in the head/tail gap for head-prefix expansion.
     */
    static long resolveHeadExpandCluster(
            long headBytes,
            long tailRemoteStart,
            long fromGapScan,
            long fromCandidates) {
        long result = -1L;
        if (fromGapScan >= headBytes && fromGapScan < tailRemoteStart) {
            result = fromGapScan;
        }
        if (fromCandidates >= headBytes
                && fromCandidates < tailRemoteStart
                && (result < 0L || fromCandidates < result)) {
            result = fromCandidates;
        }
        return result;
    }

    /** Earliest Cluster in the head prefix (for diagnostics only). */
    static long findEarliestClusterInHeadPrefix(@NonNull File headFile) {
        return scanFileForEarliestCluster(headFile, 0L);
    }

    static long earliestGapCluster(
            @NonNull List<Long> clusterCandidates,
            long headBytes,
            long tailRemoteStart) {
        long earliest = -1L;
        for (long candidate : clusterCandidates) {
            if (candidate >= headBytes && candidate < tailRemoteStart) {
                if (earliest < 0L || candidate < earliest) {
                    earliest = candidate;
                }
            }
        }
        return earliest;
    }

    private static long findEarliestGapClusterPosition(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart) {
        long earliest = Long.MAX_VALUE;
        try {
            long fromCues = VideoMkvCueParser.findEarliestClusterPosition(headFile, tailFile);
            if (fromCues >= headBytes && fromCues < tailRemoteStart) {
                earliest = minPositive(earliest, fromCues);
            }
        } catch (Exception ignored) {
        }
        long fromCuesSlice = resolveClusterFromCuesSlice(
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart);
        if (fromCuesSlice >= headBytes && fromCuesSlice < tailRemoteStart) {
            earliest = minPositive(earliest, fromCuesSlice);
        }
        for (long fromProbe : probeGapForEarliestCluster(
                appContext,
                url,
                fileSizeBytes,
                headBytes,
                tailRemoteStart)) {
            earliest = minPositive(earliest, fromProbe);
        }
        long resolved = earliest == Long.MAX_VALUE ? -1L : earliest;
        if (resolved >= 0L && (resolved < headBytes || resolved >= tailRemoteStart)) {
            return -1L;
        }
        return resolved;
    }

    private static long minPositive(long current, long candidate) {
        if (candidate < 0L) {
            return current;
        }
        return Math.min(current, candidate);
    }

    private static long scanFileForEarliestCluster(@NonNull File file, long fileStartOffset) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            final int chunkSize = 1024 * 1024;
            byte[] chunk = new byte[chunkSize];
            byte[] carry = new byte[0];
            long bytesConsumed = 0L;
            int read;
            while ((read = input.read(chunk)) != -1) {
                byte[] window = new byte[carry.length + read];
                System.arraycopy(carry, 0, window, 0, carry.length);
                System.arraycopy(chunk, 0, window, carry.length, read);
                long windowFileStart = fileStartOffset + bytesConsumed - carry.length;
                long found = VideoMkvClusterAlign.findFirstClusterOffsetInProbe(window, windowFileStart);
                if (found >= 0L) {
                    return found;
                }
                if (window.length >= 3) {
                    carry = java.util.Arrays.copyOfRange(window, window.length - 3, window.length);
                } else {
                    carry = window;
                }
                bytesConsumed += read;
            }
            if (carry.length > 0) {
                return VideoMkvClusterAlign.findFirstClusterOffsetInProbe(
                        carry,
                        fileStartOffset + bytesConsumed - carry.length);
            }
            return -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    @NonNull
    private static long[] probeGapForEarliestCluster(
            @NonNull Context appContext,
            @NonNull String url,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart) {
        if (tailRemoteStart <= headBytes || fileSizeBytes <= 0L) {
            return new long[0];
        }
        List<Long> found = new ArrayList<>(GAP_CLUSTER_PROBE_PERCENTILES.length);
        for (int percentile : GAP_CLUSTER_PROBE_PERCENTILES) {
            long candidate = (fileSizeBytes * percentile) / 100L;
            if (candidate < headBytes || candidate >= tailRemoteStart) {
                continue;
            }
            long probeStart = Math.max(headBytes, candidate - CLUSTER_ALIGN_PROBE_BACK_BYTES);
            long probeLength = Math.min(CLUSTER_ALIGN_PROBE_LENGTH_BYTES, tailRemoteStart - probeStart);
            if (probeLength <= 0L) {
                continue;
            }
            File probeFile = null;
            try {
                probeFile = File.createTempFile("thumb_mkv_gap_probe_", ".bin", appContext.getCacheDir());
                if (!downloadRange(url, probeFile, probeStart, probeLength)) {
                    continue;
                }
                byte[] probeBytes = readProbeBytes(probeFile);
                long clusterAt = VideoMkvClusterAlign.findFirstClusterOffsetInProbe(probeBytes, probeStart);
                if (clusterAt >= headBytes && clusterAt < tailRemoteStart) {
                    found.add(clusterAt);
                }
            } catch (Exception ignored) {
            } finally {
                deleteQuietly(probeFile);
            }
        }
        long[] result = new long[found.size()];
        for (int i = 0; i < found.size(); i++) {
            result[i] = found.get(i);
        }
        return result;
    }

    @NonNull
    private static HeadExpandResult maybeExpandHeadForFirstCluster(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull String base,
            @NonNull File headFile,
            long actualHead,
            long tailRemoteStart,
            long firstCluster,
            long clusterCap,
            long maxExpandedHeadBytes) {
        long targetEnd = computeExpandedHeadEnd(
                actualHead,
                firstCluster,
                clusterCap,
                tailRemoteStart,
                maxExpandedHeadBytes);
        if (targetEnd <= actualHead) {
            return new HeadExpandResult(headFile, actualHead, false);
        }
        File expanded = null;
        try {
            expanded = File.createTempFile("thumb_mkv_head_exp_", ".bin", appContext.getCacheDir());
            if (!downloadRange(url, expanded, 0L, targetEnd)) {
                deleteQuietly(expanded);
                return new HeadExpandResult(headFile, actualHead, false);
            }
            deleteQuietly(headFile);
            return new HeadExpandResult(expanded, expanded.length(), true);
        } catch (Exception e) {
            deleteQuietly(expanded);
            VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHeadExpandFail",
                    "basename=" + base + " targetEnd=" + targetEnd);
            return new HeadExpandResult(headFile, actualHead, false);
        }
    }

    @NonNull
    private static ClusterTrimResult trimClusterToFirstBoundary(
            @NonNull File clusterFile,
            long clusterRemoteStart,
            long clusterBytes) {
        if (clusterBytes <= 0L) {
            return new ClusterTrimResult(clusterRemoteStart, clusterBytes);
        }
        try {
            long prefixLen = Math.min(CLUSTER_POST_ALIGN_PREFIX_BYTES, clusterBytes);
            byte[] prefix = readFilePrefix(clusterFile, prefixLen);
            long aligned = VideoMkvClusterAlign.findFirstClusterOffsetInProbe(prefix, clusterRemoteStart);
            if (aligned < 0L || aligned <= clusterRemoteStart || aligned >= clusterRemoteStart + clusterBytes) {
                return new ClusterTrimResult(clusterRemoteStart, clusterBytes);
            }
            int trimBytes = (int) (aligned - clusterRemoteStart);
            if (trimBytes <= 0 || trimBytes >= clusterFile.length()) {
                return new ClusterTrimResult(clusterRemoteStart, clusterBytes);
            }
            File trimmed = File.createTempFile("thumb_mkv_cluster_trim_", ".bin", clusterFile.getParentFile());
            try (java.io.FileInputStream input = new java.io.FileInputStream(clusterFile);
                 FileOutputStream output = new FileOutputStream(trimmed)) {
                long skipped = input.skip(trimBytes);
                if (skipped < trimBytes) {
                    deleteQuietly(trimmed);
                    return new ClusterTrimResult(clusterRemoteStart, clusterBytes);
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            if (!clusterFile.delete() || !trimmed.renameTo(clusterFile)) {
                deleteQuietly(trimmed);
                return new ClusterTrimResult(clusterRemoteStart, clusterBytes);
            }
            long newBytes = clusterFile.length();
            return new ClusterTrimResult(aligned, newBytes);
        } catch (Exception ignored) {
            return new ClusterTrimResult(clusterRemoteStart, clusterBytes);
        }
    }

    @NonNull
    private static byte[] readFilePrefix(@NonNull File file, long maxBytes) throws java.io.IOException {
        long length = Math.min(file.length(), maxBytes);
        if (length <= 0L) {
            throw new java.io.IOException("empty cluster prefix");
        }
        byte[] data = new byte[(int) length];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            return offset == data.length ? data : java.util.Arrays.copyOf(data, offset);
        }
    }

    /**
     * Cluster byte offset to download, or {@code -1} when head/tail already cover it.
     */
    static long findClusterDownloadStart(
            @NonNull String url,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart) {
        List<Long> candidates = findClusterDownloadCandidates(
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart);
        return candidates.isEmpty() ? -1L : candidates.get(0);
    }

    @NonNull
    static List<Long> findClusterDownloadCandidates(
            @NonNull String url,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart) {
        Set<Long> ordered = new LinkedHashSet<>();
        try {
            for (long clusterPos : VideoMkvCueParser.findClusterPositionsInGap(
                    headFile,
                    tailFile,
                    headBytes,
                    tailRemoteStart,
                    MAX_CLUSTER_ATTEMPTS)) {
                ordered.add(clusterPos);
            }
        } catch (Exception ignored) {
        }
        long fromCuesSlice = resolveClusterFromCuesSlice(url, headFile, tailFile, fileSizeBytes, headBytes, tailRemoteStart);
        if (fromCuesSlice >= headBytes && fromCuesSlice < tailRemoteStart) {
            ordered.add(fromCuesSlice);
        }
        for (long secondCue : resolveAdditionalClustersFromCuesSlice(
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart,
                MAX_CLUSTER_ATTEMPTS)) {
            if (secondCue >= headBytes && secondCue < tailRemoteStart) {
                ordered.add(secondCue);
            }
        }
        for (long percentilePos : gapPercentileFallbacks(fileSizeBytes, headBytes, tailRemoteStart)) {
            ordered.add(percentilePos);
        }
        List<Long> result = new ArrayList<>(ordered);
        if (result.size() > MAX_CLUSTER_ATTEMPTS) {
            return result.subList(0, MAX_CLUSTER_ATTEMPTS);
        }
        return result;
    }

    @NonNull
    private static long[] gapPercentileFallbacks(long fileSizeBytes, long headBytes, long tailRemoteStart) {
        if (tailRemoteStart <= headBytes || fileSizeBytes <= 0L) {
            return new long[0];
        }
        List<Long> positions = new ArrayList<>(GAP_PERCENTILES.length);
        for (int percentile : GAP_PERCENTILES) {
            long pos = (fileSizeBytes * percentile) / 100L;
            if (pos >= headBytes && pos < tailRemoteStart) {
                positions.add(pos);
            }
        }
        long[] result = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            result[i] = positions.get(i);
        }
        return result;
    }

    /**
     * When Cues starts before the downloaded tail slice, fetch a Cues range via SeekHead.
     */
    private static long resolveClusterFromCuesSlice(
            @NonNull String url,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart) {
        List<Long> fromSlice = resolveClustersFromCuesSlice(
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart,
                1);
        return fromSlice.isEmpty() ? -1L : fromSlice.get(0);
    }

    @NonNull
    private static List<Long> resolveAdditionalClustersFromCuesSlice(
            @NonNull String url,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart,
            int maxCount) {
        List<Long> fromSlice = resolveClustersFromCuesSlice(
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart,
                maxCount);
        if (fromSlice.size() <= 1) {
            return fromSlice;
        }
        return fromSlice.subList(1, fromSlice.size());
    }

    @NonNull
    private static List<Long> resolveClustersFromCuesSlice(
            @NonNull String url,
            @NonNull File headFile,
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart,
            int maxCount) {
        long cuesOffset;
        try {
            cuesOffset = VideoMkvCueParser.findCuesByteOffset(headFile);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        if (cuesOffset < 0L) {
            return new ArrayList<>();
        }
        long segmentStart;
        try {
            segmentStart = VideoMkvCueParser.findSegmentStartOffset(headFile);
        } catch (Exception ignored) {
            segmentStart = 0L;
        }
        if (cuesOffset >= tailRemoteStart) {
            return clustersFromTailCuesSlice(tailFile, tailRemoteStart, cuesOffset, segmentStart, headBytes, maxCount);
        }
        long sliceBytes = Math.min(VideoMkvCueParser.cuesSliceBytes(), fileSizeBytes - cuesOffset);
        if (sliceBytes <= 0L) {
            return new ArrayList<>();
        }
        File cuesSlice = null;
        try {
            cuesSlice = File.createTempFile("thumb_mkv_cues_", ".bin", headFile.getParentFile());
            if (!downloadRange(url, cuesSlice, cuesOffset, sliceBytes)) {
                return new ArrayList<>();
            }
            byte[] cuesBytes = readFileBytes(cuesSlice);
            List<Long> inGap = new ArrayList<>();
            for (long clusterPos : VideoMkvCueParser.findAllClusterPositionsInCuesSlice(cuesBytes, segmentStart)) {
                if (clusterPos >= headBytes && clusterPos < tailRemoteStart) {
                    inGap.add(clusterPos);
                }
            }
            if (inGap.size() > maxCount) {
                return inGap.subList(0, maxCount);
            }
            return inGap;
        } catch (Exception ignored) {
            return new ArrayList<>();
        } finally {
            deleteQuietly(cuesSlice);
        }
    }

    @NonNull
    private static List<Long> clustersFromTailCuesSlice(
            @NonNull File tailFile,
            long tailRemoteStart,
            long cuesOffset,
            long segmentStart,
            long headBytes,
            int maxCount) {
        try {
            byte[] tailBytes = readFileBytes(tailFile);
            int relOffset = (int) (cuesOffset - tailRemoteStart);
            if (relOffset < 0 || relOffset >= tailBytes.length) {
                return new ArrayList<>();
            }
            byte[] cuesPart = java.util.Arrays.copyOfRange(tailBytes, relOffset, tailBytes.length);
            List<Long> inGap = new ArrayList<>();
            for (long clusterPos : VideoMkvCueParser.findAllClusterPositionsInCuesSlice(cuesPart, segmentStart)) {
                if (clusterPos >= headBytes && clusterPos < tailRemoteStart) {
                    inGap.add(clusterPos);
                }
            }
            if (inGap.size() > maxCount) {
                return inGap.subList(0, maxCount);
            }
            return inGap;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    @NonNull
    private static byte[] readFileBytes(@NonNull File file) throws java.io.IOException {
        long length = file.length();
        if (length <= 0L || length > 64L * 1024L * 1024L) {
            throw new java.io.IOException("cues slice too large");
        }
        byte[] data = new byte[(int) length];
        try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            return offset == data.length ? data : java.util.Arrays.copyOf(data, offset);
        }
    }

    static long readRemoteFileSize(@NonNull String url) {
        OkHttpMediaDataSource probe = new OkHttpMediaDataSource(url, null);
        try {
            long size = probe.getSize();
            return size > 0L ? size : 0L;
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try {
                probe.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean downloadRange(
            @NonNull String url,
            @NonNull File dest,
            long rangeStart,
            long rangeLength) {
        if (rangeLength <= 0L) {
            return false;
        }
        long rangeEnd = rangeStart + rangeLength - 1L;
        Request request = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                .build();
        try (Response response = OkHttpMediaDataSource.getClient().newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 206) {
                return false;
            }
            if (response.body() == null) {
                return false;
            }
            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(dest)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0L;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    written += read;
                    if (written >= rangeLength) {
                        break;
                    }
                }
                return written > 0L;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null) {
            file.delete();
        }
    }

}
