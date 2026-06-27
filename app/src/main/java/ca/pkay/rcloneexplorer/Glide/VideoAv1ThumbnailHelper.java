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
    private static final long DEFAULT_CLUSTER_BYTES = 32L * 1024L * 1024L;
    private static final long RELOAD_HEAD_BYTES = 64L * 1024L * 1024L;
    private static final long RELOAD_TAIL_BYTES = 32L * 1024L * 1024L;
    private static final long RELOAD_CLUSTER_BYTES = 48L * 1024L * 1024L;

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
        File clusterFile = null;
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
            long clusterRemoteStart = 0L;
            long actualCluster = 0L;
            long clusterCap = userReload ? RELOAD_CLUSTER_BYTES : DEFAULT_CLUSTER_BYTES;
            long clusterPos = findClusterDownloadStart(tailFile, fileSizeBytes, actualHead, tailRemoteStart);
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
                    }
                }
            } else {
                VideoThumbnailFetcher.logThumbPipe(appContext, "sparseClusterSkip",
                        "basename=" + base
                        + " clusterPos=" + clusterPos
                        + " headBytes=" + actualHead
                        + " tailStart=" + tailRemoteStart);
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
            Uri sparseUri = Uri.parse("sparse-mkv://" + base);
            return VideoThumbnailExoFallback.tryGrabFromDataSourceFactory(
                    appContext,
                    new VideoMkvSparseDataSource.Factory(files, sparseUri),
                    sparseUri.toString(),
                    durationMs,
                    cancellation,
                    true,
                    userReload,
                    stablePath);
        } catch (Exception e) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "sparseExtractFail",
                    "basename=" + base + " what=" + e.getClass().getSimpleName());
            return null;
        } finally {
            deleteQuietly(headFile);
            deleteQuietly(tailFile);
            deleteQuietly(clusterFile);
        }
    }

    /**
     * Cluster byte offset to download, or {@code -1} when head/tail already cover it.
     */
    static long findClusterDownloadStart(
            @NonNull File tailFile,
            long fileSizeBytes,
            long headBytes,
            long tailRemoteStart) {
        long clusterPos = -1L;
        try {
            clusterPos = VideoMkvCueParser.findEarliestClusterPosition(tailFile);
        } catch (Exception ignored) {
            clusterPos = -1L;
        }
        if (clusterPos < 0L) {
            clusterPos = fileSizeBytes / 20L;
        }
        if (clusterPos < headBytes) {
            return -1L;
        }
        if (clusterPos >= tailRemoteStart) {
            return -1L;
        }
        return clusterPos;
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
