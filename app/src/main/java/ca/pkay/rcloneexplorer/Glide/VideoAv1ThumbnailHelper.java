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
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/**
 * AV1-in-MKV thumbnails: MMR cannot decode frames; head-only partial files lack Matroska Cues.
 * Downloads head + tail byte ranges and runs Exo on a sparse {@link VideoMkvSparseDataSource}.
 *
 * <p>Steps 13C–13M added many cluster-alignment and head-expansion layers while Exo was deadlocked
 * on a blocked player looper (fixed in Step 13N). This helper keeps the core sparse path plus one
 * cue-guided cluster fallback and an HTTP reload last resort.
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

            Bitmap headTailFrame = trySparseExoWithCluster(
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
                    clusterCap);
            if (headTailFrame != null) {
                return headTailFrame;
            }
            if (cancellation.isCancelled()) {
                return null;
            }

            long clusterPos = findClusterDownloadStart(
                    url,
                    headFile,
                    tailFile,
                    fileSizeBytes,
                    actualHead,
                    tailRemoteStart);
            if (clusterPos < 0L) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterSkip",
                        "basename=" + base
                        + " clusterPos=-1"
                        + " headBytes=" + actualHead
                        + " tailStart=" + tailRemoteStart);
            } else {
                Bitmap clusterFrame = trySparseExoWithCluster(
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
                        clusterCap);
                if (clusterFrame != null) {
                    return clusterFrame;
                }
            }

            if (userReload) {
                Bitmap httpFrame = VideoThumbnailExoFallback.tryGrabAv1HttpFrame(
                        appContext,
                        url,
                        durationMs,
                        cancellation,
                        stablePath);
                if (httpFrame != null) {
                    VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHttpExoOk",
                            "basename=" + base + " userReload=" + userReload);
                    return httpFrame;
                }
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseHttpExoMiss",
                        "basename=" + base + " userReload=" + userReload);
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
            long clusterCap) {
        File clusterFile = null;
        long clusterRemoteStart = 0L;
        long actualCluster = 0L;
        try {
            if (clusterPos >= actualHead && clusterPos < tailRemoteStart) {
                long clusterLength = Math.min(clusterCap, tailRemoteStart - clusterPos);
                if (clusterLength > 0L) {
                    clusterFile = File.createTempFile("thumb_mkv_cluster_", ".bin", appContext.getCacheDir());
                    if (downloadRange(url, clusterFile, clusterPos, clusterLength)) {
                        clusterRemoteStart = clusterPos;
                        actualCluster = clusterFile.length();
                        VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterOk",
                                "basename=" + base
                                + " clusterStart=" + clusterRemoteStart
                                + " clusterBytes=" + actualCluster);
                    } else {
                        deleteQuietly(clusterFile);
                        clusterFile = null;
                        VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterFail",
                                "basename=" + base + " clusterStart=" + clusterPos);
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
                    + " userReload=" + userReload);
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
            Uri sparseUri = Uri.parse("sparse-mkv://" + base + "#" + clusterPos);
            return VideoThumbnailExoFallback.tryGrabFromDataSourceFactory(
                    appContext,
                    new VideoMkvSparseDataSource.Factory(files, sparseUri),
                    sparseUri.toString(),
                    durationMs,
                    cancellation,
                    true,
                    userReload,
                    stablePath,
                    true);
        } catch (Exception ignored) {
            return null;
        } finally {
            deleteQuietly(clusterFile);
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
        List<Long> inGap = new ArrayList<>();
        try {
            for (long clusterPos : VideoMkvCueParser.findClusterPositionsInGap(
                    headFile,
                    tailFile,
                    headBytes,
                    tailRemoteStart,
                    1)) {
                inGap.add(clusterPos);
            }
        } catch (Exception ignored) {
        }
        if (!inGap.isEmpty()) {
            return inGap;
        }
        long fromCuesSlice = resolveClusterFromCuesSlice(
                url,
                headFile,
                tailFile,
                fileSizeBytes,
                headBytes,
                tailRemoteStart);
        if (fromCuesSlice >= headBytes && fromCuesSlice < tailRemoteStart) {
            inGap.add(fromCuesSlice);
        }
        return inGap;
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
        long cuesOffset;
        try {
            cuesOffset = VideoMkvCueParser.findCuesByteOffset(headFile);
        } catch (Exception ignored) {
            return -1L;
        }
        if (cuesOffset < 0L) {
            return -1L;
        }
        long segmentStart;
        try {
            segmentStart = VideoMkvCueParser.findSegmentStartOffset(headFile);
        } catch (Exception ignored) {
            segmentStart = 0L;
        }
        if (cuesOffset >= tailRemoteStart) {
            return earliestClusterInGapFromTailCues(
                    tailFile,
                    tailRemoteStart,
                    cuesOffset,
                    segmentStart,
                    headBytes);
        }
        long sliceBytes = Math.min(VideoMkvCueParser.cuesSliceBytes(), fileSizeBytes - cuesOffset);
        if (sliceBytes <= 0L) {
            return -1L;
        }
        File cuesSlice = null;
        try {
            cuesSlice = File.createTempFile("thumb_mkv_cues_", ".bin", headFile.getParentFile());
            if (!downloadRange(url, cuesSlice, cuesOffset, sliceBytes)) {
                return -1L;
            }
            byte[] cuesBytes = readFileBytes(cuesSlice);
            long earliest = -1L;
            for (long clusterPos : VideoMkvCueParser.findAllClusterPositionsInCuesSlice(cuesBytes, segmentStart)) {
                if (clusterPos >= headBytes && clusterPos < tailRemoteStart) {
                    if (earliest < 0L || clusterPos < earliest) {
                        earliest = clusterPos;
                    }
                }
            }
            return earliest;
        } catch (Exception ignored) {
            return -1L;
        } finally {
            deleteQuietly(cuesSlice);
        }
    }

    private static long earliestClusterInGapFromTailCues(
            @NonNull File tailFile,
            long tailRemoteStart,
            long cuesOffset,
            long segmentStart,
            long headBytes) {
        try {
            byte[] tailBytes = readFileBytes(tailFile);
            int relOffset = (int) (cuesOffset - tailRemoteStart);
            if (relOffset < 0 || relOffset >= tailBytes.length) {
                return -1L;
            }
            byte[] cuesPart = java.util.Arrays.copyOfRange(tailBytes, relOffset, tailBytes.length);
            long earliest = -1L;
            for (long clusterPos : VideoMkvCueParser.findAllClusterPositionsInCuesSlice(cuesPart, segmentStart)) {
                if (clusterPos >= headBytes) {
                    if (earliest < 0L || clusterPos < earliest) {
                        earliest = clusterPos;
                    }
                }
            }
            return earliest;
        } catch (Exception ignored) {
            return -1L;
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
