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

public class VideoThumbnailFetcher implements DataFetcher<InputStream>, VideoThumbnailCancellation {

    private static final String TAG = "VideoThumbnailFetcher";
    private static final ExecutorService VIDEO_POOL = Executors.newFixedThreadPool(2);
    /** Limits sync.log spam when many parallel loads fail for the same URL. */
    private static final int MAX_FAILURE_LOG_URLS = 512;
    private static final Set<String> loggedSyncFailureUrls =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
  /** In-flight fetchers; used to cancel non-target work during exclusive user reload. */
    private static final Set<VideoThumbnailFetcher> activeFetchers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Caps {@code event=fetcherCancel} SyncLog volume (includes basename when pending). */
    private static final AtomicInteger fetcherCancelLogCount = new AtomicInteger(0);
    private static final int MAX_FETCHER_CANCEL_DEBUG_LOGS = 100;
    /** Throttled SyncLog lines for one reproducible export (see {@code event=thumbPipe}). */
    private static final java.util.concurrent.atomic.AtomicInteger thumbPipeLogCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_THUMB_PIPE_LOGS = 2500;
    /**
     * Hidden SDK key ({@code MediaMetadataRetriever.METADATA_KEY_VIDEO_CODEC_MIME_TYPE}).
     * Used only for debug suffix logging on API 30+.
     */
    private static final int METADATA_KEY_VIDEO_CODEC_MIME_TYPE = 40;
    private static final long LARGE_VIDEO_THRESHOLD_BYTES = 500L * 1024L * 1024L;

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
        activeFetchers.add(this);
        pendingTask = VIDEO_POOL.submit(() -> {
            try {
            if (cancelled) {
                logThumbPipe(appContext, "fetcherEarlyCancel",
                        "basename=" + basenameForThumbUrl(url) + " when=poolEntry " + mgrDebugSuffix());
                callback.onLoadFailed(new RuntimeException("Cancelled"));
                return;
            }
            final long t0 = SystemClock.elapsedRealtime();
            final String base = basenameForThumbUrl(url);
            final String stablePath = ThumbnailStablePath.canonicalFromServeUrl(url);
            if (ThumbnailReloadPriority.shouldDeferVideoFetch(stablePath)) {
                logThumbPipe(appContext, "fetcherDeferred",
                        "basename=" + base + " reason=exclusiveUserReload " + mgrDebugSuffix());
                callback.onLoadFailed(new RuntimeException("Deferred for exclusive user reload"));
                return;
            }
            byte[] reloadJpeg = ThumbnailReloadResultCache.get(stablePath);
            if (reloadJpeg != null) {
                logThumbPipe(appContext, "reloadCacheHit",
                        "basename=" + base + " bytes=" + reloadJpeg.length + " " + mgrDebugSuffix());
                callback.onDataReady(new ByteArrayInputStream(reloadJpeg));
                return;
            }
            final boolean preferExo = ThumbnailReloadEpoch.consumePreferExoDecode(stablePath);
            logThumbPipe(appContext, "fetcherStart",
                    "basename=" + base + " preferExo=" + preferExo + " " + mgrDebugSuffix());

            if (preferExo && !cancelled) {
                logThumbPipe(appContext, "reloadExoFirst",
                        "basename=" + base + " epoch=" + ThumbnailReloadEpoch.getEpochForVideoUrl(url)
                                + " " + mgrDebugSuffix());
                Bitmap exoFrame = VideoThumbnailExoFallback.tryGrabFirstFrame(
                        appContext, url, 0L, VideoThumbnailFetcher.this, true, true);
                if (exoFrame != null) {
                    deliverJpegFrame(exoFrame, base, t0, callback);
                    return;
                }
                if (cancelled) {
                    callback.onLoadFailed(new RuntimeException("Cancelled"));
                    return;
                }
                logThumbPipe(appContext, "reloadExoFirstMiss",
                        "basename=" + base + " fallback=mmr " + mgrDebugSuffix());
            }

            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            OkHttpMediaDataSource dataSource = new OkHttpMediaDataSource(url, appContext);
            boolean mmrReleased = false;
            boolean dataSourceClosed = false;
            try {
                long fileSizeBytes = dataSource.getSize();
                boolean largeVideo = fileSizeBytes >= LARGE_VIDEO_THRESHOLD_BYTES;
                mmr.setDataSource(dataSource);
                final String debugSuffix = frameExtractDebugSuffix(mmr);
                final long durationMs = readDurationMsFromRetriever(mmr);
                final String codecMime = VideoAv1ThumbnailHelper.readVideoCodecMime(mmr);
                Bitmap frame = null;
                boolean exoAttempted = false;

                if (largeVideo && !cancelled) {
                    long tB2 = SystemClock.elapsedRealtime();
                    exoAttempted = true;
                    logThumbPipe(appContext, "b2FirstStart",
                            "basename=" + base + " fileSizeBytes=" + fileSizeBytes
                                    + " thresholdBytes=" + LARGE_VIDEO_THRESHOLD_BYTES
                                    + " durationMs=" + durationMs + " " + mgrDebugSuffix());
                    frame = VideoThumbnailExoFallback.tryGrabFirstFrame(
                            appContext, url, durationMs, VideoThumbnailFetcher.this, true, false);
                    long b2Ms = SystemClock.elapsedRealtime() - tB2;
                    logThumbPipe(appContext, "b2FirstEnd",
                            "basename=" + base + " result=" + (frame != null ? "OK" : "null")
                                    + " hasFrame=" + (frame != null)
                                    + " cancelled=" + cancelled + " b2Ms=" + b2Ms + " " + mgrDebugSuffix());
                }

                if (frame == null && cancelled) {
                    callback.onLoadFailed(new RuntimeException("Cancelled"));
                    return;
                }

                if (frame == null) {
                    long tMmr = SystemClock.elapsedRealtime();
                    frame = extractNonBlackFrame(mmr);
                    long mmrMs = SystemClock.elapsedRealtime() - tMmr;
                    logThumbPipe(appContext, "mmrEnd",
                            "basename=" + base + " result=" + (frame != null ? "frame" : "noFrame")
                                    + " cancelled=" + cancelled + " mmrMs=" + mmrMs + " " + mgrDebugSuffix());
                } else {
                    logThumbPipe(appContext, "mmrSkip",
                            "basename=" + base + " reason=b2FirstHasFrame " + mgrDebugSuffix());
                }
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
                    if (!cancelled
                            && frame == null
                            && VideoAv1ThumbnailHelper.isAv1Codec(codecMime)
                            && fileSizeBytes > 0L) {
                        long tSparse = SystemClock.elapsedRealtime();
                        frame = VideoAv1ThumbnailHelper.tryGrabFromSparseHeadTail(
                                appContext,
                                url,
                                fileSizeBytes,
                                durationMs,
                                VideoThumbnailFetcher.this,
                                stablePath,
                                false);
                        logThumbPipe(appContext, "sparseExoEnd",
                                "basename=" + base + " result=" + (frame != null ? "frame" : "null")
                                        + " cancelled=" + cancelled
                                        + " sparseMs=" + (SystemClock.elapsedRealtime() - tSparse)
                                        + " " + mgrDebugSuffix());
                    }
                    if (!cancelled && frame == null && !exoAttempted) {
                        long tExo = SystemClock.elapsedRealtime();
                        int reloadEpoch = ThumbnailReloadEpoch.getEpochForVideoUrl(url);
                        frame = VideoThumbnailExoFallback.tryGrabFirstFrame(
                                appContext, url, durationMs, VideoThumbnailFetcher.this, true, true);
                        long exoMs = SystemClock.elapsedRealtime() - tExo;
                        logThumbPipe(appContext, "exoEnd",
                                "basename=" + base + " result=" + (frame != null ? "frame" : "null")
                                        + " cancelled=" + cancelled + " exoMs=" + exoMs + " " + mgrDebugSuffix());
                    } else {
                        logThumbPipe(appContext, "exoSkip",
                                "basename=" + base + " reason="
                                        + (cancelled ? "cancelledBeforeExo" : "exoAlreadyAttempted")
                                        + " " + mgrDebugSuffix());
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
                deliverJpegFrame(frame, base, t0, callback);
            } catch (Exception e) {
                FLog.e(TAG, "loadData: failed to extract frame from %s", url);
                logThumbPipe(appContext, "fetcherFail",
                        "basename=" + base + " what=" + e.getClass().getSimpleName()
                                + " msg=" + scrubMsg(e.getMessage()) + " " + mgrDebugSuffix());
                if (!cancelled) {
                    logThumbPipe(appContext, "fetcherFailTryExo",
                            "basename=" + base + " " + mgrDebugSuffix());
                    Bitmap exoFrame = VideoThumbnailExoFallback.tryGrabFirstFrame(
                            appContext, url, 0L, VideoThumbnailFetcher.this, true, true);
                    if (exoFrame != null) {
                        deliverJpegFrame(exoFrame, base, t0, callback);
                        return;
                    }
                }
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
            } finally {
                activeFetchers.remove(VideoThumbnailFetcher.this);
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

    /** Clears the once-per-URL failure throttle so user reload can log and retry cleanly. */
    static void clearFailureLogForUrl(@NonNull String url) {
        loggedSyncFailureUrls.remove(url);
    }

    /**
     * Cancels every in-flight video thumbnail fetch except the file the user is reloading.
     */
    static void cancelAllExcept(@NonNull String targetStablePath) {
        String target = ThumbnailStablePath.normalize(targetStablePath);
        for (VideoThumbnailFetcher fetcher : activeFetchers) {
            String stablePath = ThumbnailStablePath.canonicalFromServeUrl(fetcher.url);
            if (!target.equals(ThumbnailStablePath.normalize(stablePath))) {
                fetcher.cancel();
            }
        }
    }

    private boolean deliverJpegFrame(
            @NonNull Bitmap frame,
            @NonNull String base,
            long t0,
            @NonNull DataCallback<? super InputStream> callback) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        frame.compress(Bitmap.CompressFormat.JPEG, 75, baos);
        frame.recycle();
        logThumbPipe(appContext, "decodeOk",
                "basename=" + base + " totalMs=" + (SystemClock.elapsedRealtime() - t0)
                        + " " + mgrDebugSuffix());
        callback.onDataReady(new ByteArrayInputStream(baos.toByteArray()));
        return true;
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
        String mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
        String codec = VideoAv1ThumbnailHelper.readVideoCodecMime(mmr);
        if (codec == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            codec = mmr.extractMetadata(METADATA_KEY_VIDEO_CODEC_MIME_TYPE);
        }
        return "durationMs=" + (durationStr != null ? durationStr : "?")
                + " bitrate=" + (bitrateStr != null ? bitrateStr : "?")
                + " mime=" + (mime != null ? mime : "?")
                + " codec=" + (codec != null ? codec : "?");
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

    @Override
    public boolean isCancelled() {
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
        int reloadEpoch = ThumbnailReloadEpoch.getEpochForVideoUrl(url);
        long[] timestamps = reloadEpoch > 0
                ? buildReloadThumbnailProbeTimesUs(durationMs, durationUs, reloadEpoch)
                : buildThumbnailProbeTimesUs(durationMs, durationUs);
        BestFrameSoFar best = new BestFrameSoFar();

        for (long ts : timestamps) {
            if (cancelled) {
                break;
            }
            Bitmap frame = retrieveFrameAtTime(mmr, ts, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) {
                best.consider(frame);
                if (best.hasStrongRepresentative()) {
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
                    if (best.hasStrongRepresentative()) {
                        return best.getFrame();
                    }
                }
            }
        }
        return best.getFrame();
    }

    /**
     * Ordered probe times (microseconds). CLOSEST_SYNC pass uses these first so common cases stay
     * fast; PREVIOUS/NEXT pass reuses the same set for sparse keyframes / odd muxes. We bias away
     * from near-zero timestamps so fades, title cards, and transition washes are less likely to win.
     */
    private static long[] buildThumbnailProbeTimesUs(long durationMs, long durationUs) {
        if (durationMs <= 0) {
            LinkedHashSet<Long> u = new LinkedHashSet<>();
            u.add(2_000_000L);
            u.add(5_000_000L);
            u.add(8_000_000L);
            u.add(1_000_000L);
            u.add(10_000_000L);
            u.add(500_000L);
            u.add(0L);
            return longSetToArray(u);
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        // Prefer post-intro and early/mid-scene probes before near-zero timestamps.
        ordered.add(clampTimeUs(2_000_000L, durationUs));
        ordered.add(clampTimeUs(5_000_000L, durationUs));
        ordered.add(clampTimeUs(8_000_000L, durationUs));
        ordered.add(clampTimeUs(durationUs / 8, durationUs));
        ordered.add(clampTimeUs(durationUs / 5, durationUs));
        ordered.add(clampTimeUs(durationUs / 3, durationUs));
        ordered.add(clampTimeUs(durationUs / 2, durationUs));
        ordered.add(clampTimeUs(1_000_000L, durationUs));
        ordered.add(clampTimeUs(500_000L, durationUs));
        ordered.add(clampTimeUs(Math.max(1L, durationUs / 100), durationUs));
        ordered.add(clampTimeUs(durationUs / 20, durationUs));
        ordered.add(clampTimeUs(durationUs / 10, durationUs));
        if (durationUs > 3_000_000L) {
            ordered.add(clampTimeUs(durationUs - 3_000_000L, durationUs));
        }
        if (durationUs > 12_000_000L) {
            ordered.add(clampTimeUs(durationUs - 6_000_000L, durationUs));
        }
        if (durationUs > 20_000_000L) {
            ordered.add(clampTimeUs(durationUs - 10_000_000L, durationUs));
        }
        if (durationUs > 8_000_000L) {
            ordered.add(clampTimeUs(durationUs - 1_000_000L, durationUs));
            ordered.add(clampTimeUs(durationUs - 6_000_000L, durationUs));
        }
        ordered.add(0L);
        return longSetToArray(ordered);
    }

    /**
     * Reload-specific probes: rotate through 0%, 10%, 25%, and 50% of duration based on reload epoch.
     */
    private static long[] buildReloadThumbnailProbeTimesUs(
            long durationMs,
            long durationUs,
            int reloadEpoch) {
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
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (int i = 0; i < fractionsUs.length; i++) {
            ordered.add(clampTimeUs(fractionsUs[(start + i) % fractionsUs.length], durationUs));
        }
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

    private static final class BestFrameSoFar {
        private static final double STRONG_REPRESENTATIVE_SCORE = 42d;
        @Nullable
        private Bitmap frame;
        private double brightnessScore = -1;
        private double informativeScore = -1;
        private boolean hasRepresentative;
        private boolean flatColorLike = true;

        void consider(@NonNull Bitmap candidate) {
            VideoFrameQualityScorer.FrameQuality quality = VideoFrameQualityScorer.score(candidate);
            boolean representativeCandidate = !quality.isPoorRepresentativeCandidate();
            if (representativeCandidate) {
                boolean betterRepresentative = !hasRepresentative
                        || quality.informativeScore() > informativeScore
                        || (quality.informativeScore() == informativeScore
                        && (quality.brightness() > brightnessScore
                        || (quality.brightness() == brightnessScore
                        && flatColorLike
                        && !quality.isFlatColorLike())));
                if (betterRepresentative) {
                    replaceFrame(candidate, quality, true);
                } else {
                    candidate.recycle();
                }
                return;
            }
            if (hasRepresentative) {
                candidate.recycle();
                return;
            }
            boolean brighterFallback = quality.brightness() > brightnessScore;
            boolean sameBrightnessButMoreInformative = quality.brightness() == brightnessScore
                    && (quality.informativeScore() > informativeScore
                    || (quality.informativeScore() == informativeScore
                    && flatColorLike
                    && !quality.isFlatColorLike()));
            if (brighterFallback || sameBrightnessButMoreInformative) {
                replaceFrame(candidate, quality, false);
            } else {
                candidate.recycle();
            }
        }

        private void replaceFrame(
                @NonNull Bitmap candidate,
                @NonNull VideoFrameQualityScorer.FrameQuality quality,
                boolean representative) {
            if (frame != null) {
                frame.recycle();
            }
            frame = candidate;
            brightnessScore = quality.brightness();
            informativeScore = quality.informativeScore();
            flatColorLike = quality.isFlatColorLike();
            hasRepresentative = representative;
        }

        boolean hasRepresentative() {
            return frame != null && hasRepresentative;
        }

        boolean hasStrongRepresentative() {
            return hasRepresentative() && informativeScore >= STRONG_REPRESENTATIVE_SCORE;
        }

        @Nullable
        Bitmap getFrame() {
            return frame;
        }
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
