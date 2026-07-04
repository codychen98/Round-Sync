package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.Media3ExtensionRenderers;

/**
 * One-frame grab via Media3 + FFmpeg extension when {@link android.media.MediaMetadataRetriever}
 * finds no usable frame.
 *
 * <p>Threading contract: the {@link ExoPlayer} instance lives on the dedicated
 * {@code video_thumb_exo} {@link HandlerThread} (its "application looper"). ExoPlayer delivers all
 * externally visible state updates — playback state, buffered position, player errors, and
 * {@code onRenderedFirstFrame} — as messages on that looper. The looper must therefore never be
 * blocked while a grab is waiting; otherwise {@code getPlaybackState()} stays frozen at
 * {@code STATE_BUFFERING} and {@code getBufferedPosition()} at 0 regardless of the data source
 * (this was the root cause of every historical {@code exoPrepareTimeout bufEndMs=0}). All waiting
 * happens on the calling (Glide/direct-extract) thread; every player interaction is posted to the
 * player looper.
 */
final class VideoThumbnailExoFallback {

    private static final String TAG = "VideoThumbExoFb";
    /** Prepare-phase surface size; upgraded to the video's own size once the format is known. */
    private static final int OUT_W = 320;
    private static final int OUT_H = 180;
    /**
     * Capture cap / fallback. MMR-extracted thumbnails are full video resolution; capturing the Exo
     * frame at 320x180 produced visibly blurrier grid thumbnails than neighbors (Step 15A).
     */
    private static final int MAX_CAPTURE_DIM = 1280;
    private static final int CAPTURE_FALLBACK_W = 1280;
    private static final int CAPTURE_FALLBACK_H = 720;
    private static final long PREPARE_TIMEOUT_MS = 20_000L;
    private static final long USER_RELOAD_PREPARE_TIMEOUT_MS = 60_000L;
    /** AV1 user reload: cold software decode + large MKV demux can exceed 60s on low-end devices. */
    private static final long AV1_USER_RELOAD_PREPARE_TIMEOUT_MS = 180_000L;
    private static final long RENDER_WAIT_MS = 12_000L;
    private static final long PIXEL_COPY_MS = 5_000L;
    private static final long MAX_DURATION_MS = 6L * 60L * 60L * 1000L;
    private static final long SETUP_WAIT_MS = 15_000L;
    private static final long EXO_READ_WAIT_MS = 2_000L;

    private static final Object EXO_THREAD_LOCK = new Object();
    /** Serializes grabs (one player at a time), matching the previous single-queue behavior. */
    private static final Semaphore GRAB_PERMIT = new Semaphore(1);
    @Nullable
    private static Handler sExoHandler;

    private VideoThumbnailExoFallback() {
    }

    @NonNull
    private static String scrubExoMsg(@Nullable String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Host, port, path segment count, and URL length for SyncLog (avoids dumping full auth path).
     */
    @NonNull
    private static String thumbUrlForSyncLog(@NonNull String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost() != null ? u.getHost() : "";
            int port = u.getPort();
            String portPart = port > 0 ? String.valueOf(port) : "default";
            int nSegs = u.getPathSegments() != null ? u.getPathSegments().size() : 0;
            return "uriHost=" + host + " uriPort=" + portPart + " pathSegs=" + nSegs + " urlChars=" + url.length();
        } catch (Exception e) {
            return "uriParseFail urlChars=" + url.length();
        }
    }

    @NonNull
    private static String stablePathForUrl(@NonNull String url) {
        return ThumbnailStablePath.fromServeUrl(url);
    }

    @Nullable
    private static String resolveLiveUrlOrNull(@NonNull String stablePath) {
        String base = ThumbnailServerManager.getInstance().getCurrentBaseUrlOrNull();
        if (base == null || base.isEmpty()) {
            return null;
        }
        return stablePath.startsWith("/") ? base + stablePath : base + "/" + stablePath;
    }

    @NonNull
    private static String exoPlayerSnapshotForSyncLog(@NonNull ExoPlayer player) {
        PlaybackException err = player.getPlayerError();
        String errPart;
        if (err == null) {
            errPart = "playerError=none";
        } else {
            errPart = "playerErrorMsg=" + scrubExoMsg(err.getMessage())
                    + " errorCode=" + err.errorCode
                    + " errorCodeName=" + PlaybackException.getErrorCodeName(err.errorCode);
        }
        long dur = player.getDuration();
        String durPart = dur == C.TIME_UNSET ? "unset" : String.valueOf(dur);
        return "playbackState=" + player.getPlaybackState()
                + " isLoading=" + player.isLoading()
                + " playWhenReady=" + player.getPlayWhenReady()
                + " posMs=" + player.getCurrentPosition()
                + " bufEndMs=" + player.getBufferedPosition()
                + " durationMs=" + durPart
                + " " + errPart;
    }

    @NonNull
    private static Handler exoThumbHandler() {
        synchronized (EXO_THREAD_LOCK) {
            if (sExoHandler == null) {
                HandlerThread ht = new HandlerThread("video_thumb_exo");
                ht.start();
                sExoHandler = new Handler(ht.getLooper());
            }
            return sExoHandler;
        }
    }

    /** Read a value from the player on its own looper; null when the read times out. */
    private interface ExoRead<T> {
        T read(@NonNull ExoPlayer player);
    }

    @Nullable
    private static <T> T readFromExo(
            @NonNull Handler exoH,
            @NonNull AtomicReference<ExoPlayer> playerRef,
            @NonNull ExoRead<T> reader) {
        AtomicReference<T> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        exoH.post(() -> {
            try {
                ExoPlayer p = playerRef.get();
                if (p != null) {
                    out.set(reader.read(p));
                }
            } catch (Exception ignore) {
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(EXO_READ_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return out.get();
    }

    @NonNull
    private static String snapshotForLog(
            @NonNull Handler exoH, @NonNull AtomicReference<ExoPlayer> playerRef) {
        String snapshot = readFromExo(exoH, playerRef,
                VideoThumbnailExoFallback::exoPlayerSnapshotForSyncLog);
        return snapshot != null ? snapshot : "snapshotUnavailable";
    }

    /** Listener state shared between the caller thread and the player looper. */
    private static final class ExoSession implements Player.Listener {
        final CountDownLatch readyOrError = new CountDownLatch(1);
        final AtomicReference<PlaybackException> playerError = new AtomicReference<>();
        final AtomicReference<CountDownLatch> renderLatch = new AtomicReference<>();

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                readyOrError.countDown();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            playerError.set(error);
            readyOrError.countDown();
        }

        @Override
        public void onRenderedFirstFrame() {
            CountDownLatch latch = renderLatch.get();
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @Nullable
    static Bitmap tryGrabFirstFrame(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailFetcher owner) {
        return tryGrabFirstFrame(appContext, url, durationMs, owner, false, false);
    }

    @Nullable
    static Bitmap tryGrabFirstFrame(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck) {
        return tryGrabFirstFrame(appContext, url, durationMs, cancellation, relaxDurationCheck, false);
    }

    @Nullable
    static Bitmap tryGrabLocalPrefixFrame(
            @NonNull Context appContext,
            @NonNull String absolutePath,
            long durationMs,
            @NonNull String stablePath,
            @NonNull VideoThumbnailCancellation cancellation) {
        return tryGrabLocalPrefixFrame(
                appContext, absolutePath, durationMs, stablePath, cancellation, false);
    }

    @Nullable
    static Bitmap tryGrabLocalPrefixFrame(
            @NonNull Context appContext,
            @NonNull String absolutePath,
            long durationMs,
            @NonNull String stablePath,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean av1Codec) {
        String fileUri = Uri.fromFile(new java.io.File(absolutePath)).toString();
        return tryGrabFirstFrameInternal(
                appContext,
                fileUri,
                durationMs,
                cancellation,
                true,
                true,
                stablePath,
                av1Codec);
    }

    /** HTTP Exo for AV1 reload when local/sparse paths miss (matches {@code VideoPlayerActivity} streaming). */
    @Nullable
    static Bitmap tryGrabAv1HttpFrame(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            @Nullable String stablePath) {
        return tryGrabFirstFrameInternal(
                appContext,
                url,
                durationMs,
                cancellation,
                true,
                true,
                stablePath,
                true);
    }

    @Nullable
    private static Bitmap tryGrabFirstFrameInternal(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            @Nullable String reloadEpochStablePath) {
        return tryGrabFirstFrameInternal(
                appContext,
                url,
                durationMs,
                cancellation,
                relaxDurationCheck,
                extendedPrepareTimeout,
                reloadEpochStablePath,
                false);
    }

    @Nullable
    private static Bitmap tryGrabFirstFrameInternal(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            @Nullable String reloadEpochStablePath,
            boolean av1Codec) {
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=apiLtN");
            return null;
        }
        if (cancellation.isCancelled()) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=cancelledAtExoEntry");
            return null;
        }
        if (!relaxDurationCheck && (durationMs <= 0 || durationMs > MAX_DURATION_MS)) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=durationOutOfRange durationMs=" + durationMs);
            return null;
        }
        if (relaxDurationCheck && durationMs > MAX_DURATION_MS) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=durationTooLong durationMs=" + durationMs);
            return null;
        }
        if (!acquireGrabPermit(appContext, base, cancellation)) {
            return null;
        }
        try {
            Bitmap frame = grabWithFreeLooper(
                    appContext, url, durationMs, cancellation, relaxDurationCheck,
                    extendedPrepareTimeout, av1Codec, reloadEpochStablePath, av1Codec, null);
            if (frame == null && !av1Codec && !cancellation.isCancelled()) {
                frame = grabWithFreeLooper(
                        appContext, url, durationMs, cancellation, relaxDurationCheck,
                        extendedPrepareTimeout, true, reloadEpochStablePath, false, null);
                if (frame != null) {
                    VideoThumbnailFetcher.logThumbPipe(appContext, "exoPlaybackPrimeRetryOk",
                            "basename=" + base);
                }
            }
            return frame;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoInterrupted",
                    "basename=" + base);
            return null;
        } catch (Exception e) {
            FLog.w(TAG, "Exo thumbnail fallback failed: %s", e.getMessage());
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoThreadException",
                    "basename=" + base + " msg=" + scrubExoMsg(e.getMessage()));
            return null;
        } finally {
            GRAB_PERMIT.release();
        }
    }

    @Nullable
    static Bitmap tryGrabFirstFrame(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout) {
        return tryGrabFirstFrameInternal(
                appContext, url, durationMs, cancellation, relaxDurationCheck, extendedPrepareTimeout, null);
    }

    @Nullable
    static Bitmap tryGrabFromDataSourceFactory(
            @NonNull Context appContext,
            @NonNull androidx.media3.datasource.DataSource.Factory dataSourceFactory,
            @NonNull String logUrl,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            @Nullable String reloadEpochStablePath) {
        return tryGrabFromDataSourceFactory(
                appContext,
                dataSourceFactory,
                logUrl,
                durationMs,
                cancellation,
                relaxDurationCheck,
                extendedPrepareTimeout,
                reloadEpochStablePath,
                false);
    }

    @Nullable
    static Bitmap tryGrabFromDataSourceFactory(
            @NonNull Context appContext,
            @NonNull androidx.media3.datasource.DataSource.Factory dataSourceFactory,
            @NonNull String logUrl,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            @Nullable String reloadEpochStablePath,
            boolean av1Codec) {
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(logUrl);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=apiLtN");
            return null;
        }
        if (cancellation.isCancelled()) {
            return null;
        }
        if (!acquireGrabPermit(appContext, base, cancellation)) {
            return null;
        }
        try {
            return grabWithFreeLooper(
                    appContext, logUrl, durationMs, cancellation, relaxDurationCheck,
                    extendedPrepareTimeout, true, reloadEpochStablePath, av1Codec, dataSourceFactory);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            FLog.w(TAG, "Sparse Exo thumbnail failed: %s", e.getMessage());
            return null;
        } finally {
            GRAB_PERMIT.release();
        }
    }

    private static boolean acquireGrabPermit(
            @NonNull Context appContext,
            @NonNull String base,
            @NonNull VideoThumbnailCancellation cancellation) {
        try {
            while (!GRAB_PERMIT.tryAcquire(250L, TimeUnit.MILLISECONDS)) {
                if (cancellation.isCancelled()) {
                    VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                            "basename=" + base + " reason=cancelledWaitingForExoSlot");
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long resolvePrepareTimeoutMs(boolean extendedPrepareTimeout, boolean av1Codec) {
        if (av1Codec && extendedPrepareTimeout) {
            return AV1_USER_RELOAD_PREPARE_TIMEOUT_MS;
        }
        return extendedPrepareTimeout ? USER_RELOAD_PREPARE_TIMEOUT_MS : PREPARE_TIMEOUT_MS;
    }

    /**
     * Runs on the calling thread. Player setup, prepare, and every player call are posted to the
     * player looper; the caller only waits on latches fed by {@link ExoSession} callbacks, keeping
     * the player looper free to process state updates.
     */
    @Nullable
    private static Bitmap grabWithFreeLooper(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            boolean primePlayback,
            @Nullable String reloadEpochStablePath,
            boolean av1Codec,
            @Nullable androidx.media3.datasource.DataSource.Factory overrideDataSourceFactory)
            throws InterruptedException {
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        final boolean localFile = url.regionMatches(true, 0, "file:", 0, 5);
        final boolean sparseSource = overrideDataSourceFactory != null;
        final String stablePath = localFile || sparseSource ? url : stablePathForUrl(url);
        final boolean playImmediately = primePlayback || sparseSource || av1Codec;

        final String mediaUri;
        if (localFile || sparseSource) {
            mediaUri = url;
        } else {
            String liveUrl = resolveLiveUrlOrNull(stablePath);
            if (liveUrl == null) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                        "basename=" + base + " reason=serveNotReady");
                return null;
            }
            mediaUri = liveUrl;
        }

        Handler exoH = exoThumbHandler();
        ExoSession session = new ExoSession();
        AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        AtomicReference<ImageReader> readerRef = new AtomicReference<>();
        AtomicReference<Exception> setupError = new AtomicReference<>();
        CountDownLatch setupDone = new CountDownLatch(1);
        exoH.post(() -> {
            try {
                androidx.media3.exoplayer.DefaultRenderersFactory renderersFactory = av1Codec
                        ? Media3ExtensionRenderers.newAv1PreferRenderersFactory(appContext)
                        : Media3ExtensionRenderers.newDefaultRenderersFactory(appContext);
                ExoPlayer player = new ExoPlayer.Builder(appContext, renderersFactory)
                        .setLooper(Looper.myLooper())
                        .build();
                // Registered before any further setup so the caller's finally-release
                // still cleans up when a later setup step throws.
                playerRef.set(player);
                ImageReader reader = ImageReader.newInstance(OUT_W, OUT_H, PixelFormat.RGBA_8888, 2);
                readerRef.set(reader);
                player.addListener(session);
                player.setVideoSurface(reader.getSurface());
                player.setPlayWhenReady(playImmediately);
                player.setVolume(0f);
                androidx.media3.datasource.DataSource.Factory dataSourceFactory;
                if (overrideDataSourceFactory != null) {
                    dataSourceFactory = overrideDataSourceFactory;
                } else if (localFile) {
                    dataSourceFactory = new DefaultDataSource.Factory(appContext);
                } else {
                    dataSourceFactory =
                            new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                                    OkHttpMediaDataSource.getClient())
                                    .setUserAgent(Util.getUserAgent(
                                            appContext, appContext.getPackageName()));
                }
                player.setMediaSource(new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(mediaUri)));
                player.prepare();
            } catch (Exception e) {
                setupError.set(e);
            } finally {
                setupDone.countDown();
            }
        });
        try {
            String engine = sparseSource
                    ? "SparseMkvDataSource ProgressiveMediaSource"
                    : "OkHttpDataSource ProgressiveMediaSource";
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareStart",
                    "basename=" + base + " durationMs=" + durationMs + " relaxDuration=" + relaxDurationCheck
                            + " primePlayback=" + playImmediately
                            + (av1Codec ? " av1ExoPreferExt=true" : "")
                            + " " + thumbUrlForSyncLog(localFile || sparseSource ? url : mediaUri)
                            + " httpEngine=" + engine
                            + " extensionRendererMode=" + (av1Codec ? "prefer" : "on"));
            if (!setupDone.await(SETUP_WAIT_MS, TimeUnit.MILLISECONDS)) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoSetupTimeout",
                        "basename=" + base + " waitMs=" + SETUP_WAIT_MS);
                return null;
            }
            if (setupError.get() != null) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoThreadException",
                        "basename=" + base + " msg=" + scrubExoMsg(setupError.get().getMessage()));
                return null;
            }

            if (!waitForReady(appContext, base, exoH, playerRef, session, cancellation,
                    extendedPrepareTimeout, av1Codec, playImmediately)) {
                return null;
            }

            upgradeCaptureSurfaceForVideoFormat(appContext, base, exoH, playerRef, readerRef);

            long effectiveDurationMs = durationMs;
            if (effectiveDurationMs <= 0) {
                Long playerDur = readFromExo(exoH, playerRef, ExoPlayer::getDuration);
                if (playerDur != null && playerDur != C.TIME_UNSET && playerDur > 0) {
                    effectiveDurationMs = playerDur;
                } else if (relaxDurationCheck) {
                    effectiveDurationMs = 60_000L;
                }
            }
            String recordStablePath = reloadEpochStablePath != null
                    ? ThumbnailStablePath.normalize(reloadEpochStablePath)
                    : ThumbnailStablePath.canonicalFromServeUrl(url);
            int reloadEpoch = ThumbnailReloadEpoch.get(recordStablePath);
            long lastSourceMs = ThumbnailReloadEpoch.getLastSourcePositionMs(recordStablePath);
            long[] seekAttemptsMs = VideoThumbnailSeekProbe.exoSeekAttemptsMs(
                    effectiveDurationMs, reloadEpoch, lastSourceMs);
            for (long seekMs : seekAttemptsMs) {
                if (cancellation.isCancelled()) {
                    break;
                }
                Bitmap bitmap = captureFrameAtSeekMs(
                        appContext, base, exoH, playerRef, readerRef, session, cancellation, seekMs);
                if (bitmap != null) {
                    ThumbnailReloadEpoch.recordSourcePositionMs(recordStablePath, seekMs);
                    VideoThumbnailFetcher.logThumbPipe(appContext, "exoSeekOk",
                            "basename=" + base + " seekMs=" + seekMs + " epoch=" + reloadEpoch
                                    + (VideoThumbnailSeekProbe.isSameFrameMs(seekMs, lastSourceMs)
                                            ? " sameAsLastSource=true" : ""));
                    return bitmap;
                }
            }
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoAllSeeksFailed",
                    "basename=" + base + " attempts=" + seekAttemptsMs.length
                            + " durationMs=" + effectiveDurationMs + " epoch=" + reloadEpoch);
            return null;
        } finally {
            exoH.post(() -> {
                ExoPlayer p = playerRef.getAndSet(null);
                if (p != null) {
                    try {
                        p.release();
                    } catch (Exception ignore) {
                    }
                }
                ImageReader r = readerRef.getAndSet(null);
                if (r != null) {
                    try {
                        r.close();
                    } catch (Exception ignore) {
                    }
                }
            });
        }
    }

    /**
     * Replaces the small prepare-phase {@link ImageReader} with one sized to the decoded video
     * (aspect-correct, longest edge capped at {@link #MAX_CAPTURE_DIM}) so the captured frame is
     * not a 320x180 upscale. Falls back to 1280x720 when the format is unavailable. The swap and
     * the old reader's close run on the player looper so the surface change is ordered with
     * subsequent {@code seekTo} render requests.
     */
    private static void upgradeCaptureSurfaceForVideoFormat(
            @NonNull Context appContext,
            @NonNull String base,
            @NonNull Handler exoH,
            @NonNull AtomicReference<ExoPlayer> playerRef,
            @NonNull AtomicReference<ImageReader> readerRef) {
        androidx.media3.common.Format format = readFromExo(exoH, playerRef, ExoPlayer::getVideoFormat);
        int targetW;
        int targetH;
        if (format != null && format.width > 0 && format.height > 0) {
            boolean swapDims = format.rotationDegrees == 90 || format.rotationDegrees == 270;
            int srcW = swapDims ? format.height : format.width;
            int srcH = swapDims ? format.width : format.height;
            float scale = Math.min(1f, (float) MAX_CAPTURE_DIM / Math.max(srcW, srcH));
            targetW = Math.max(2, Math.round(srcW * scale));
            targetH = Math.max(2, Math.round(srcH * scale));
        } else {
            targetW = CAPTURE_FALLBACK_W;
            targetH = CAPTURE_FALLBACK_H;
        }
        ImageReader current = readerRef.get();
        if (current != null && current.getWidth() == targetW && current.getHeight() == targetH) {
            return;
        }
        final ImageReader upgraded;
        try {
            upgraded = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2);
        } catch (Exception e) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoCaptureSizeFail",
                    "basename=" + base + " w=" + targetW + " h=" + targetH
                            + " msg=" + scrubExoMsg(e.getMessage()));
            return;
        }
        CountDownLatch swapped = new CountDownLatch(1);
        AtomicReference<Boolean> applied = new AtomicReference<>(false);
        exoH.post(() -> {
            try {
                ExoPlayer p = playerRef.get();
                if (p == null) {
                    upgraded.close();
                    return;
                }
                ImageReader old = readerRef.getAndSet(upgraded);
                p.setVideoSurface(upgraded.getSurface());
                applied.set(true);
                if (old != null) {
                    try {
                        old.close();
                    } catch (Exception ignore) {
                    }
                }
            } finally {
                swapped.countDown();
            }
        });
        try {
            if (!swapped.await(EXO_READ_WAIT_MS, TimeUnit.MILLISECONDS)) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoCaptureSizeFail",
                        "basename=" + base + " reason=swapTimeout w=" + targetW + " h=" + targetH);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (Boolean.TRUE.equals(applied.get())) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoCaptureSize",
                    "basename=" + base + " w=" + targetW + " h=" + targetH
                            + " fmt=" + (format != null ? format.width + "x" + format.height
                                    + " rot=" + format.rotationDegrees : "unknown"));
        }
    }

    @Nullable
    private static Bitmap captureFrameAtSeekMs(
            @NonNull Context appContext,
            @NonNull String base,
            @NonNull Handler exoH,
            @NonNull AtomicReference<ExoPlayer> playerRef,
            @NonNull AtomicReference<ImageReader> readerRef,
            @NonNull ExoSession session,
            @NonNull VideoThumbnailCancellation cancellation,
            long seekMs) throws InterruptedException {
        CountDownLatch rendered = new CountDownLatch(1);
        session.renderLatch.set(rendered);
        exoH.post(() -> {
            ExoPlayer p = playerRef.get();
            if (p != null) {
                p.seekTo(Math.max(0L, seekMs));
            }
        });
        boolean gotFrame = rendered.await(RENDER_WAIT_MS, TimeUnit.MILLISECONDS);
        session.renderLatch.set(null);
        if (!gotFrame || cancellation.isCancelled()) {
            Integer state = readFromExo(exoH, playerRef, ExoPlayer::getPlaybackState);
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoNoRenderedFrame",
                    "basename=" + base + " seekMs=" + seekMs + " gotFrame=" + gotFrame
                            + " cancelled=" + cancellation.isCancelled()
                            + " playbackState=" + (state != null ? state : -1));
            return null;
        }

        ImageReader reader = readerRef.get();
        if (reader == null) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(
                reader.getWidth(), reader.getHeight(), Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1);
        AtomicReference<Integer> copyResult = new AtomicReference<>(PixelCopy.ERROR_UNKNOWN);
        Handler main = new Handler(Looper.getMainLooper());
        PixelCopy.request(reader.getSurface(), bitmap, r -> {
            copyResult.set(r);
            copied.countDown();
        }, main);
        if (!copied.await(PIXEL_COPY_MS, TimeUnit.MILLISECONDS) || cancellation.isCancelled()) {
            bitmap.recycle();
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoPixelCopyTimeout",
                    "basename=" + base + " seekMs=" + seekMs
                            + " cancelled=" + cancellation.isCancelled());
            return null;
        }
        if (copyResult.get() != PixelCopy.SUCCESS) {
            FLog.w(TAG, "PixelCopy failed code=%s", copyResult.get());
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoPixelCopyFail",
                    "basename=" + base + " seekMs=" + seekMs + " code=" + copyResult.get());
            bitmap.recycle();
            return null;
        }
        return bitmap;
    }

    /**
     * Waits on the calling thread for {@link ExoSession#readyOrError}; never blocks the player
     * looper. When the initial attempt did not start playback, primes {@code playWhenReady} after
     * 3s of zero-buffer buffering (progressive HTTP sources sometimes stall without it).
     */
    private static boolean waitForReady(
            @NonNull Context appContext,
            @NonNull String basename,
            @NonNull Handler exoH,
            @NonNull AtomicReference<ExoPlayer> playerRef,
            @NonNull ExoSession session,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean extendedPrepareTimeout,
            boolean av1Codec,
            boolean playImmediately)
            throws InterruptedException {
        long timeoutMs = resolvePrepareTimeoutMs(extendedPrepareTimeout, av1Codec);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        boolean primed = playImmediately;
        while (System.currentTimeMillis() < deadline) {
            if (cancellation.isCancelled()) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareAbort",
                        "basename=" + basename + " reason=cancelled");
                return false;
            }
            if (session.readyOrError.await(250L, TimeUnit.MILLISECONDS)) {
                if (session.playerError.get() != null) {
                    VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareFail",
                            "basename=" + basename + " " + snapshotForLog(exoH, playerRef));
                    return false;
                }
                return true;
            }
            if (!primed && System.currentTimeMillis() - startedAt >= 3_000L) {
                primed = true;
                exoH.post(() -> {
                    ExoPlayer p = playerRef.get();
                    if (p != null
                            && p.getPlaybackState() == Player.STATE_BUFFERING
                            && p.getBufferedPosition() <= 0L) {
                        p.setPlayWhenReady(true);
                        VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrimePlaybackForBuffer",
                                "basename=" + basename + " " + exoPlayerSnapshotForSyncLog(p));
                    }
                });
            }
        }
        VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareTimeout",
                "basename=" + basename + " " + snapshotForLog(exoH, playerRef));
        return false;
    }
}
