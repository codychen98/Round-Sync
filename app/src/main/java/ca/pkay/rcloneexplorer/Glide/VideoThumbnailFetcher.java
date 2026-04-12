package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SyncLog;

public class VideoThumbnailFetcher implements DataFetcher<InputStream> {

    private static final String TAG = "VideoThumbnailFetcher";
    private static final ExecutorService VIDEO_POOL = Executors.newFixedThreadPool(2);
    /** Limits sync.log spam when many parallel loads fail for the same URL. */
    private static final int MAX_FAILURE_LOG_URLS = 512;
    private static final Set<String> loggedSyncFailureUrls =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Caps {@code event=fetcherCancel} SyncLog volume (includes basename when pending). */
    private static final AtomicInteger fetcherCancelLogCount = new AtomicInteger(0);
    private static final int MAX_FETCHER_CANCEL_DEBUG_LOGS = 100;
    /** Throttled SyncLog lines for one reproducible export (see {@code event=thumbPipe}). */
    private static final java.util.concurrent.atomic.AtomicInteger thumbPipeLogCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_THUMB_PIPE_LOGS = 2500;

    private final String url;
    private final Context appContext;
    private volatile boolean cancelled;
    private volatile Future<?> pendingTask;

    public VideoThumbnailFetcher(@NonNull String url, @NonNull Context appContext) {
        this.url = url;
        this.appContext = appContext;
    }

    /**
     * Structured pipeline log for Menu → Logs exports. {@code phase} values include:
     * {@code fetcherStart}, {@code mmrEnd}, {@code exoPrepareStart}, {@code exoPrepareFail},
     * {@code exoPrepareTimeout}, {@code exoSkip}, {@code exoEnd}, {@code decodeOk},
     * {@code fetcherFail}, {@code fetcherEarlyCancel}.
     */
    static void logThumbPipe(@Nullable Context ctx, @NonNull String phase, @NonNull String attrs) {
        if (ctx == null) {
            return;
        }
        if (thumbPipeLogCount.incrementAndGet() > MAX_THUMB_PIPE_LOGS) {
            return;
        }
        SyncLog.info(ctx.getApplicationContext(), "VidThumbDbg", "event=thumbPipe phase=" + phase + " " + attrs);
    }

    @NonNull
    static String basenameForThumbUrl(@NonNull String thumbUrl) {
        String seg = Uri.parse(thumbUrl).getLastPathSegment();
        return seg != null ? seg : thumbUrl;
    }

    @NonNull
    private static String scrubMsg(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void loadData(@NonNull Priority priority,
                         @NonNull DataCallback<? super InputStream> callback) {
        if (cancelled) {
            logThumbPipe(appContext, "fetcherEarlyCancel",
                    "basename=" + basenameForThumbUrl(url) + " when=loadDataEntry " + mgrDebugSuffix());
            callback.onLoadFailed(new RuntimeException("Cancelled"));
            return;
        }
        pendingTask = VIDEO_POOL.submit(() -> {
            if (cancelled) {
                logThumbPipe(appContext, "fetcherEarlyCancel",
                        "basename=" + basenameForThumbUrl(url) + " when=poolEntry " + mgrDebugSuffix());
                callback.onLoadFailed(new RuntimeException("Cancelled"));
                return;
            }
            final long t0 = SystemClock.elapsedRealtime();
            final String base = basenameForThumbUrl(url);
            logThumbPipe(appContext, "fetcherStart", "basename=" + base + " " + mgrDebugSuffix());
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            OkHttpMediaDataSource dataSource = new OkHttpMediaDataSource(url, appContext);
            boolean mmrReleased = false;
            boolean dataSourceClosed = false;
            try {
                mmr.setDataSource(dataSource);
                final String debugSuffix = frameExtractDebugSuffix(mmr);
                final long durationMs = readDurationMsFromRetriever(mmr);
                long tMmr = SystemClock.elapsedRealtime();
                Bitmap frame = extractNonBlackFrame(mmr);
                long mmrMs = SystemClock.elapsedRealtime() - tMmr;
                logThumbPipe(appContext, "mmrEnd",
                        "basename=" + base + " result=" + (frame != null ? "frame" : "noFrame")
                                + " cancelled=" + cancelled + " mmrMs=" + mmrMs + " " + mgrDebugSuffix());
                if (frame == null) {
                    try {
                        mmr.release();
                    } catch (Exception ignore) {
                    }
                    mmrReleased = true;
                    try {
                        dataSource.close();
                    } catch (Exception ignore) {
                    }
                    dataSourceClosed = true;
                    if (!cancelled) {
                        long tExo = SystemClock.elapsedRealtime();
                        frame = VideoThumbnailExoFallback.tryGrabFirstFrame(
                                appContext, url, durationMs, VideoThumbnailFetcher.this);
                        long exoMs = SystemClock.elapsedRealtime() - tExo;
                        logThumbPipe(appContext, "exoEnd",
                                "basename=" + base + " result=" + (frame != null ? "frame" : "null")
                                        + " cancelled=" + cancelled + " exoMs=" + exoMs + " " + mgrDebugSuffix());
                    } else {
                        logThumbPipe(appContext, "exoSkip",
                                "basename=" + base + " reason=cancelledBeforeExo " + mgrDebugSuffix());
                    }
                    if (frame == null) {
                        logThumbnailSyncFailureOnce(
                                "event=videoFetchFail reason=noFrame "
                                        + debugSuffix
                                        + " "
                                        + mgrDebugSuffix()
                                        + " url=");
                        callback.onLoadFailed(
                                new RuntimeException("No frame extracted from " + url));
                        return;
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
                frame.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                frame.recycle();
                logThumbPipe(appContext, "decodeOk",
                        "basename=" + base + " totalMs=" + (SystemClock.elapsedRealtime() - t0)
                                + " " + mgrDebugSuffix());
                callback.onDataReady(new ByteArrayInputStream(baos.toByteArray()));
            } catch (Exception e) {
                FLog.e(TAG, "loadData: failed to extract frame from %s", url);
                logThumbPipe(appContext, "fetcherFail",
                        "basename=" + base + " what=" + e.getClass().getSimpleName()
                                + " msg=" + scrubMsg(e.getMessage()) + " " + mgrDebugSuffix());
                logThumbnailSyncFailureOnce(
                        "event=videoFetchFail reason=exception what="
                                + e.getClass().getSimpleName()
                                + " msg=" + e.getMessage()
                                + " | " + mgrDebugSuffix()
                                + " url=");
                callback.onLoadFailed(e);
            } finally {
                if (!mmrReleased) {
                    try {
                        mmr.release();
                    } catch (Exception ignore) {
                    }
                }
                if (!dataSourceClosed) {
                    try {
                        dataSource.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
    }

    private void logThumbnailSyncFailureOnce(String messagePrefix) {
        if (loggedSyncFailureUrls.size() >= MAX_FAILURE_LOG_URLS && !loggedSyncFailureUrls.contains(url)) {
            return;
        }
        if (!loggedSyncFailureUrls.add(url)) {
            return;
        }
        SyncLog.error(appContext, "VidThumbDbg", messagePrefix + url);
    }

    @NonNull
    private static String mgrDebugSuffix() {
        ThumbnailServerManager m = ThumbnailServerManager.getInstance();
        return "mgrState=" + m.getSyncState() + " serveGen=" + m.getServeGeneration();
    }

    @NonNull
    private static String frameExtractDebugSuffix(@NonNull MediaMetadataRetriever mmr) {
        String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        String bitrateStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
        return "durationMs=" + (durationStr != null ? durationStr : "?")
                + " bitrate=" + (bitrateStr != null ? bitrateStr : "?");
    }

    private static long readDurationMsFromRetriever(@NonNull MediaMetadataRetriever mmr) {
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

    boolean isCancelled() {
        return cancelled;
    }

    private Bitmap extractNonBlackFrame(MediaMetadataRetriever mmr) {
        String durationStr = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
        long durationMs = 0;
        if (durationStr != null) {
            try {
                durationMs = Long.parseLong(durationStr);
            } catch (NumberFormatException ignored) {
            }
        }
        long durationUs = durationMs > 0 ? durationMs * 1000L : 0L;
        long[] timestamps = buildThumbnailProbeTimesUs(durationMs, durationUs);
        BrightestSoFar best = new BrightestSoFar();

        for (long ts : timestamps) {
            if (cancelled) {
                break;
            }
            Bitmap frame = retrieveFrameAtTime(mmr, ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                best.consider(frame);
                if (best.isBrightEnough()) {
                    return best.getFrame();
                }
            }
        }
        for (long ts : timestamps) {
            if (cancelled) {
                break;
            }
            for (int option : new int[]{
                    MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                    MediaMetadataRetriever.OPTION_NEXT_SYNC,
            }) {
                Bitmap frame = retrieveFrameAtTime(mmr, ts, option);
                if (frame != null) {
                    best.consider(frame);
                    if (best.isBrightEnough()) {
                        return best.getFrame();
                    }
                }
            }
        }
        return best.getFrame();
    }

    /**
     * Ordered probe times (microseconds). CLOSEST_SYNC pass uses these first so common cases stay
     * fast; PREVIOUS/NEXT pass reuses the same set for sparse keyframes / odd muxes.
     */
    private static long[] buildThumbnailProbeTimesUs(long durationMs, long durationUs) {
        if (durationMs <= 0) {
            LinkedHashSet<Long> u = new LinkedHashSet<>();
            u.add(2_000_000L);
            u.add(5_000_000L);
            u.add(500_000L);
            u.add(1_000_000L);
            u.add(10_000_000L);
            u.add(0L);
            return longSetToArray(u);
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        ordered.add(clampTimeUs(durationUs / 10, durationUs));
        ordered.add(clampTimeUs(durationUs / 4, durationUs));
        ordered.add(clampTimeUs(durationUs / 2, durationUs));
        ordered.add(clampTimeUs(Math.max(1L, durationUs / 100), durationUs));
        ordered.add(clampTimeUs(durationUs / 20, durationUs));
        ordered.add(clampTimeUs(durationUs * 3 / 4, durationUs));
        ordered.add(clampTimeUs(durationUs * 9 / 10, durationUs));
        ordered.add(clampTimeUs(2_000_000L, durationUs));
        ordered.add(clampTimeUs(500_000L, durationUs));
        ordered.add(clampTimeUs(1_000_000L, durationUs));
        if (durationUs > 3_000_000L) {
            ordered.add(clampTimeUs(durationUs - 1_000_000L, durationUs));
        }
        ordered.add(0L);
        return longSetToArray(ordered);
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

    private static long[] longSetToArray(LinkedHashSet<Long> values) {
        long[] out = new long[values.size()];
        int i = 0;
        for (Long v : values) {
            out[i++] = v;
        }
        return out;
    }

    @Nullable
    private Bitmap retrieveFrameAtTime(
            @NonNull MediaMetadataRetriever mmr,
            long timeUs,
            int option) {
        Bitmap frame = mmr.getFrameAtTime(timeUs, option);
        if (frame == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                frame = mmr.getScaledFrameAtTime(timeUs, option, 384, 216);
            } catch (RuntimeException ignored) {
            }
        }
        return frame;
    }

    private static final class BrightestSoFar {
        @Nullable
        private Bitmap frame;
        private double score = -1;

        void consider(@NonNull Bitmap candidate) {
            double brightness = averageBrightness(candidate);
            if (brightness >= 30) {
                if (frame != null) {
                    frame.recycle();
                }
                frame = candidate;
                score = brightness;
            } else if (brightness > score) {
                if (frame != null) {
                    frame.recycle();
                }
                frame = candidate;
                score = brightness;
            } else {
                candidate.recycle();
            }
        }

        boolean isBrightEnough() {
            return frame != null && score >= 30;
        }

        @Nullable
        Bitmap getFrame() {
            return frame;
        }
    }

    private static double averageBrightness(Bitmap bitmap) {
        int sampleSize = 8;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        long totalBrightness = 0;
        int samples = 0;

        for (int x = 0; x < sampleSize; x++) {
            for (int y = 0; y < sampleSize; y++) {
                int px = w * x / sampleSize;
                int py = h * y / sampleSize;
                int pixel = bitmap.getPixel(px, py);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                totalBrightness += (r + g + b);
                samples++;
            }
        }
        return (double) totalBrightness / (samples * 3);
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void cancel() {
        cancelled = true;
        Future<?> task = pendingTask;
        boolean hadPendingTask = task != null;
        if (hadPendingTask) {
            task.cancel(true);
        }
        maybeLogFetcherCancelDebug(hadPendingTask);
    }

    /**
     * Confirms Glide forwarded cancellation into this fetcher (see {@code SourceGenerator.cancel}
     * in Glide 4.x). Debug-only and throttled; remove or tighten if no longer needed.
     */
    private void maybeLogFetcherCancelDebug(boolean hadPendingTask) {
        int n = fetcherCancelLogCount.incrementAndGet();
        if (n > MAX_FETCHER_CANCEL_DEBUG_LOGS) {
            return;
        }
        ThumbnailServerManager m = ThumbnailServerManager.getInstance();
        String basename = basenameForThumbUrl(url);
        SyncLog.info(
                appContext,
                "VidThumbDbg",
                "event=fetcherCancel n="
                        + n
                        + " basename="
                        + basename
                        + " hadPendingTask="
                        + hadPendingTask
                        + " mgrState="
                        + m.getSyncState()
                        + " serveGen="
                        + m.getServeGeneration());
        if (hadPendingTask) {
            logThumbPipe(appContext, "fetcherCancelPending",
                    "basename=" + basename + " n=" + n + " mgrState=" + m.getSyncState()
                            + " serveGen=" + m.getServeGeneration());
        }
    }

    @NonNull
    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {
        return DataSource.REMOTE;
    }
}
