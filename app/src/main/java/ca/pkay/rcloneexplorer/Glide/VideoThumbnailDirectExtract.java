package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import ca.pkay.rcloneexplorer.util.SyncLog;
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Blocking thumbnail extraction for user-initiated reload. Runs outside Glide so work is not
 * cancelled when the list row rebinds.
 */
final class VideoThumbnailDirectExtract {

    private static final String LOG_TAG = "ThumbReloadDbg";
    private static final long PARTIAL_DOWNLOAD_BYTES = 96L * 1024L * 1024L;

    private VideoThumbnailDirectExtract() {
    }

    @Nullable
    static byte[] extractJpegForUserReload(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull String stablePath) {
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        final long t0 = SystemClock.elapsedRealtime();
        log(appContext, "directExtractStart basename=" + base + " stablePath=" + stablePath);

        String liveUrl = resolveLiveUrl(stablePath, url);
        MmrExtractResult mmrResult = extractWithMmr(appContext, liveUrl, stablePath);
        Bitmap frame = mmrResult.frame;
        String codecMime = mmrResult.codecMime;
        long durationMs = mmrResult.durationMs;
        String stage = frame != null ? "mmr" : "mmrMiss";

        if (frame == null && VideoAv1ThumbnailHelper.isAv1Codec(codecMime)) {
            liveUrl = resolveLiveUrl(stablePath, url);
            long fileSizeBytes = VideoAv1ThumbnailHelper.readRemoteFileSize(liveUrl);
            if (fileSizeBytes > 0L) {
                frame = VideoAv1ThumbnailHelper.tryGrabFromSparseHeadTail(
                        appContext,
                        liveUrl,
                        fileSizeBytes,
                        durationMs,
                        VideoThumbnailCancellation.NEVER_CANCELLED,
                        stablePath,
                        true);
                stage = frame != null ? "sparseHeadTail" : "sparseHeadTailMiss";
            }
        }
        if (frame == null && !VideoAv1ThumbnailHelper.isAv1Codec(codecMime)) {
            liveUrl = resolveLiveUrl(stablePath, url);
            frame = VideoThumbnailExoFallback.tryGrabFirstFrame(
                    appContext,
                    liveUrl,
                    durationMs,
                    VideoThumbnailCancellation.NEVER_CANCELLED,
                    true,
                    true);
            stage = frame != null ? "exo" : "exoMiss";
        }
        if (frame == null && !VideoAv1ThumbnailHelper.isAv1Codec(codecMime)) {
            liveUrl = resolveLiveUrl(stablePath, url);
            frame = extractFromPartialLocalFile(appContext, liveUrl, stablePath);
            stage = frame != null ? "partialFile" : "partialFileMiss";
        }
        if (frame == null) {
            log(appContext, "directExtractFail basename=" + base + " totalMs=" + (SystemClock.elapsedRealtime() - t0));
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            frame.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            log(appContext, "directExtractOk basename=" + base + " stage=" + stage
                    + " totalMs=" + (SystemClock.elapsedRealtime() - t0));
            return baos.toByteArray();
        } finally {
            frame.recycle();
        }
    }

    private static final class MmrExtractResult {
        @Nullable
        final Bitmap frame;
        final long durationMs;
        @Nullable
        final String codecMime;

        MmrExtractResult(@Nullable Bitmap frame, long durationMs, @Nullable String codecMime) {
            this.frame = frame;
            this.durationMs = durationMs;
            this.codecMime = codecMime;
        }
    }

    @NonNull
    private static MmrExtractResult extractWithMmr(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull String stablePath) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        OkHttpMediaDataSource dataSource = new OkHttpMediaDataSource(url, appContext);
        try {
            mmr.setDataSource(dataSource);
            long durationMs = readDurationMs(mmr);
            String codecMime = VideoAv1ThumbnailHelper.readVideoCodecMime(mmr);
            int reloadEpoch = ThumbnailReloadEpoch.get(ThumbnailStablePath.normalize(stablePath));
            Bitmap frame = extractFrameWithReloadOffsets(mmr, durationMs, reloadEpoch, stablePath);
            return new MmrExtractResult(frame, durationMs, codecMime);
        } catch (Exception ignored) {
            return new MmrExtractResult(null, 0L, null);
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
            try {
                dataSource.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static long readDurationMs(@NonNull MediaMetadataRetriever mmr) {
        String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        if (durationStr == null) {
            return 0L;
        }
        try {
            return Long.parseLong(durationStr);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @Nullable
    private static Bitmap extractFrameWithReloadOffsets(
            @NonNull MediaMetadataRetriever mmr,
            long durationMs,
            int reloadEpoch,
            @NonNull String stablePath) {
        long durationUs = durationMs > 0 ? durationMs * 1000L : 0L;
        String normalizedPath = ThumbnailStablePath.normalize(stablePath);
        long lastSourceMs = ThumbnailReloadEpoch.getLastSourcePositionMs(normalizedPath);
        long[] timestamps = reloadEpoch > 0
                ? buildReloadTimesUs(durationMs, durationUs, reloadEpoch, lastSourceMs)
                : buildDefaultTimesUs(durationMs, durationUs);
        for (long ts : timestamps) {
            Bitmap frame = mmr.getFrameAtTime(ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                ThumbnailReloadEpoch.recordSourcePositionMs(normalizedPath, ts / 1_000L);
                return frame;
            }
        }
        return null;
    }

    private static long[] buildReloadTimesUs(
            long durationMs,
            long durationUs,
            int reloadEpoch,
            long lastSourcePositionMs) {
        long[] fractionsUs;
        if (durationMs > 0) {
            fractionsUs = new long[] {
                    0L,
                    durationUs / 10,
                    durationUs / 4,
                    durationUs / 2,
            };
        } else {
            fractionsUs = new long[] {
                    0L,
                    1_000_000L,
                    2_500_000L,
                    5_000_000L,
            };
        }
        int start = reloadEpoch % fractionsUs.length;
        long[] ordered = new long[fractionsUs.length];
        for (int i = 0; i < fractionsUs.length; i++) {
            ordered[i] = clampTimeUs(fractionsUs[(start + i) % fractionsUs.length], durationUs);
        }
        return VideoThumbnailSeekProbe.deprioritizeLastSourceUs(ordered, lastSourcePositionMs);
    }

    private static long[] buildDefaultTimesUs(long durationMs, long durationUs) {
        if (durationMs <= 0) {
            return new long[] { 2_000_000L, 5_000_000L, 1_000_000L, 0L };
        }
        return new long[] {
                0L,
                clampTimeUs(2_000_000L, durationUs),
                clampTimeUs(5_000_000L, durationUs),
                clampTimeUs(durationUs / 8, durationUs),
                clampTimeUs(durationUs / 4, durationUs),
                clampTimeUs(durationUs / 2, durationUs),
        };
    }

    private static long clampTimeUs(long timeUs, long durationUs) {
        if (timeUs < 0) {
            return 0L;
        }
        if (durationUs > 0 && timeUs > durationUs) {
            return durationUs;
        }
        return timeUs;
    }

    @Nullable
    private static Bitmap extractFromPartialLocalFile(
            @NonNull Context appContext,
            @NonNull String url,
            @NonNull String stablePath) {
        File temp = null;
        try {
            temp = File.createTempFile("thumb_reload_", ".bin", appContext.getCacheDir());
            if (!downloadPrefixToFile(url, temp, PARTIAL_DOWNLOAD_BYTES)) {
                log(appContext, "partialDownloadFail stablePath=" + stablePath);
                return null;
            }
            log(appContext, "partialDownloadOk stablePath=" + stablePath + " bytes=" + temp.length());
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            long durationMs = 0L;
            try {
                mmr.setDataSource(temp.getAbsolutePath());
                durationMs = readDurationMs(mmr);
                int reloadEpoch = ThumbnailReloadEpoch.get(ThumbnailStablePath.normalize(stablePath));
                Bitmap frame = extractFrameWithReloadOffsets(mmr, durationMs, reloadEpoch, stablePath);
                if (frame == null) {
                    frame = mmr.getFrameAtTime(2_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (frame == null) {
                    frame = VideoThumbnailExoFallback.tryGrabLocalPrefixFrame(
                            appContext,
                            temp.getAbsolutePath(),
                            durationMs,
                            stablePath,
                            VideoThumbnailCancellation.NEVER_CANCELLED);
                    if (frame != null) {
                        log(appContext, "partialFileExoOk stablePath=" + stablePath);
                    }
                }
                return frame;
            } finally {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log(appContext, "partialFileExtractFail stablePath=" + stablePath
                    + " what=" + e.getClass().getSimpleName());
            return null;
        } finally {
            if (temp != null) {
                temp.delete();
            }
        }
    }

    private static boolean downloadPrefixToFile(
            @NonNull String url,
            @NonNull File dest,
            long maxBytes) {
        Request request = new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-" + (maxBytes - 1L))
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
                    if (written >= maxBytes) {
                        break;
                    }
                }
                return written > 0L;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void log(@NonNull Context appContext, @NonNull String message) {
        SyncLog.info(appContext.getApplicationContext(), LOG_TAG, "event=" + message);
    }

    @NonNull
    private static String resolveLiveUrl(@NonNull String stablePath, @NonNull String fallbackUrl) {
        String base = ThumbnailServerManager.getInstance().getCurrentBaseUrlOrNull();
        if (base == null || base.isEmpty()) {
            return fallbackUrl;
        }
        return stablePath.startsWith("/") ? base + stablePath : base + "/" + stablePath;
    }
}
