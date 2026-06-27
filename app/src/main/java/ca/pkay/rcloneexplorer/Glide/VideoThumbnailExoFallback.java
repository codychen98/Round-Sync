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
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.Media3ExtensionRenderers;

/**
 * One-frame grab via Media3 + FFmpeg extension when {@link android.media.MediaMetadataRetriever}
 * finds no usable frame. ExoPlayer must run on the looper passed to {@link ExoPlayer.Builder}
 * (not Glide's worker pool), so work is marshalled to a dedicated {@link HandlerThread}.
 */
final class VideoThumbnailExoFallback {

    private static final String TAG = "VideoThumbExoFb";
    private static final int OUT_W = 320;
    private static final int OUT_H = 180;
    private static final long PREPARE_TIMEOUT_MS = 20_000L;
    private static final long USER_RELOAD_PREPARE_TIMEOUT_MS = 60_000L;
    private static final long RENDER_WAIT_MS = 12_000L;
    private static final long PIXEL_COPY_MS = 5_000L;
    private static final long MAX_DURATION_MS = 6L * 60L * 60L * 1000L;

    private static final Object EXO_THREAD_LOCK = new Object();
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
        String fileUri = Uri.fromFile(new java.io.File(absolutePath)).toString();
        return tryGrabFirstFrameInternal(
                appContext,
                fileUri,
                durationMs,
                cancellation,
                true,
                true,
                stablePath);
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
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        final long outerWaitMs = (extendedPrepareTimeout ? USER_RELOAD_PREPARE_TIMEOUT_MS : PREPARE_TIMEOUT_MS)
                + (RENDER_WAIT_MS * VideoThumbnailSeekProbe.exoSeekAttemptsMs(
                        durationMs > 0 ? durationMs : 60_000L, 0).length)
                + PIXEL_COPY_MS + 5_000L;
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
        Handler exoH = exoThumbHandler();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<String> errorMsg = new AtomicReference<>();
        exoH.post(() -> {
            try {
                result.set(grabOnExoLooper(
                        appContext, url, durationMs, cancellation, relaxDurationCheck,
                        extendedPrepareTimeout, false, reloadEpochStablePath));
                if (result.get() == null && !cancellation.isCancelled()) {
                    Bitmap primed = grabOnExoLooper(
                            appContext, url, durationMs, cancellation, relaxDurationCheck,
                            extendedPrepareTimeout, true, reloadEpochStablePath);
                    if (primed != null) {
                        VideoThumbnailFetcher.logThumbPipe(appContext, "exoPlaybackPrimeRetryOk",
                                "basename=" + base);
                    }
                    result.set(primed);
                }
            } catch (Exception e) {
                errorMsg.set(e.getMessage());
                FLog.w(TAG, "Exo thumbnail fallback failed: %s", e.getMessage());
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(outerWaitMs, TimeUnit.MILLISECONDS)) {
                FLog.w(TAG, "Exo thumbnail fallback timed out waiting for exo thread");
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoOuterTimeout",
                        "basename=" + base + " waitMs=" + outerWaitMs);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoInterrupted",
                    "basename=" + base);
            return null;
        }
        if (errorMsg.get() != null) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoThreadException",
                    "basename=" + base + " msg=" + scrubExoMsg(errorMsg.get()));
            return null;
        }
        return result.get();
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
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(logUrl);
        final long outerWaitMs = (extendedPrepareTimeout ? USER_RELOAD_PREPARE_TIMEOUT_MS : PREPARE_TIMEOUT_MS)
                + (RENDER_WAIT_MS * VideoThumbnailSeekProbe.exoSeekAttemptsMs(
                        durationMs > 0 ? durationMs : 60_000L, 0).length)
                + PIXEL_COPY_MS + 5_000L;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=apiLtN");
            return null;
        }
        if (cancellation.isCancelled()) {
            return null;
        }
        Handler exoH = exoThumbHandler();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        exoH.post(() -> {
            try {
                result.set(grabOnExoLooper(
                        appContext, logUrl, durationMs, cancellation, relaxDurationCheck,
                        extendedPrepareTimeout, true, reloadEpochStablePath, dataSourceFactory));
            } catch (Exception e) {
                FLog.w(TAG, "Sparse Exo thumbnail failed: %s", e.getMessage());
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(outerWaitMs, TimeUnit.MILLISECONDS)) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoOuterTimeout",
                        "basename=" + base + " waitMs=" + outerWaitMs + " source=sparseMkv");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }

    @Nullable
    private static Bitmap grabOnExoLooper(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            boolean primePlayback,
            @Nullable String reloadEpochStablePath) throws Exception {
        return grabOnExoLooper(
                appContext, url, durationMs, cancellation, relaxDurationCheck, extendedPrepareTimeout,
                primePlayback, reloadEpochStablePath, null);
    }

    @Nullable
    private static Bitmap grabOnExoLooper(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean relaxDurationCheck,
            boolean extendedPrepareTimeout,
            boolean primePlayback,
            @Nullable String reloadEpochStablePath,
            @Nullable androidx.media3.datasource.DataSource.Factory overrideDataSourceFactory)
            throws Exception {
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        final boolean localFile = url.regionMatches(true, 0, "file:", 0, 5);
        final boolean sparseSource = overrideDataSourceFactory != null;
        final String stablePath = localFile || sparseSource ? url : stablePathForUrl(url);
        ExoPlayer player = null;
        ImageReader reader = null;
        try {
            player = new ExoPlayer.Builder(appContext, Media3ExtensionRenderers.newDefaultRenderersFactory(appContext))
                    .setLooper(Looper.myLooper())
                    .build();
            reader = ImageReader.newInstance(OUT_W, OUT_H, PixelFormat.RGBA_8888, 2);
            player.setVideoSurface(reader.getSurface());
            player.setPlayWhenReady(primePlayback || sparseSource);
            player.setVolume(0f);

            androidx.media3.datasource.DataSource.Factory dataSourceFactory;
            if (overrideDataSourceFactory != null) {
                dataSourceFactory = overrideDataSourceFactory;
            } else {
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory httpFactory =
                        new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(OkHttpMediaDataSource.getClient())
                                .setUserAgent(Util.getUserAgent(appContext, appContext.getPackageName()));
                dataSourceFactory = localFile
                        ? new DefaultDataSource.Factory(appContext)
                        : httpFactory;
            }
            ProgressiveMediaSource.Factory progressiveFactory =
                    new ProgressiveMediaSource.Factory(dataSourceFactory);
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
            MediaItem item = MediaItem.fromUri(mediaUri);
            player.setMediaSource(progressiveFactory.createMediaSource(item));
            String engine = sparseSource
                    ? "SparseMkvDataSource ProgressiveMediaSource"
                    : "OkHttpDataSource ProgressiveMediaSource";
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareStart",
                    "basename=" + base + " durationMs=" + durationMs + " relaxDuration=" + relaxDurationCheck
                            + " primePlayback=" + (primePlayback || sparseSource)
                            + " " + thumbUrlForSyncLog(localFile || sparseSource ? url : mediaUri)
                            + " httpEngine=" + engine
                            + " extensionRendererMode=on");
            player.prepare();

            if (!waitForReady(appContext, base, player, cancellation, extendedPrepareTimeout)) {
                return null;
            }
            long effectiveDurationMs = durationMs;
            if (effectiveDurationMs <= 0) {
                long playerDur = player.getDuration();
                if (playerDur != C.TIME_UNSET && playerDur > 0) {
                    effectiveDurationMs = playerDur;
                } else if (relaxDurationCheck) {
                    effectiveDurationMs = 60_000L;
                }
            }
            int reloadEpoch = reloadEpochStablePath != null
                    ? ThumbnailReloadEpoch.get(ThumbnailStablePath.normalize(reloadEpochStablePath))
                    : ThumbnailReloadEpoch.getEpochForVideoUrl(url);
            long[] seekAttemptsMs = VideoThumbnailSeekProbe.exoSeekAttemptsMs(
                    effectiveDurationMs, reloadEpoch);
            for (long seekMs : seekAttemptsMs) {
                if (cancellation.isCancelled()) {
                    break;
                }
                Bitmap bitmap = captureFrameAtSeekMs(
                        appContext, base, player, reader, cancellation, seekMs);
                if (bitmap != null) {
                    VideoThumbnailFetcher.logThumbPipe(appContext, "exoSeekOk",
                            "basename=" + base + " seekMs=" + seekMs + " epoch=" + reloadEpoch);
                    return bitmap;
                }
            }
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoAllSeeksFailed",
                    "basename=" + base + " attempts=" + seekAttemptsMs.length
                            + " durationMs=" + effectiveDurationMs + " epoch=" + reloadEpoch);
            return null;
        } finally {
            if (player != null) {
                player.release();
            }
            if (reader != null) {
                reader.close();
            }
        }
    }

    @Nullable
    private static Bitmap captureFrameAtSeekMs(
            @NonNull Context appContext,
            @NonNull String base,
            @NonNull ExoPlayer player,
            @NonNull ImageReader reader,
            @NonNull VideoThumbnailCancellation cancellation,
            long seekMs) throws InterruptedException {
            CountDownLatch rendered = new CountDownLatch(1);
            Player.Listener listener = new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    rendered.countDown();
                }
            };
            player.addListener(listener);
            player.seekTo(Math.max(0L, seekMs));
            boolean gotFrame = rendered.await(RENDER_WAIT_MS, TimeUnit.MILLISECONDS);
            player.removeListener(listener);
            if (!gotFrame || cancellation.isCancelled()) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoNoRenderedFrame",
                        "basename=" + base + " seekMs=" + seekMs + " gotFrame=" + gotFrame
                                + " cancelled=" + cancellation.isCancelled()
                                + " playbackState=" + player.getPlaybackState());
                return null;
            }

            Bitmap bitmap = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
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

    private static boolean waitForReady(
            @NonNull Context appContext,
            @NonNull String basename,
            @NonNull ExoPlayer player,
            @NonNull VideoThumbnailCancellation cancellation,
            boolean extendedPrepareTimeout)
            throws InterruptedException {
        long timeoutMs = extendedPrepareTimeout ? USER_RELOAD_PREPARE_TIMEOUT_MS : PREPARE_TIMEOUT_MS;
        long deadline = System.currentTimeMillis() + timeoutMs;
        long startedAt = System.currentTimeMillis();
        boolean primedPlayback = player.getPlayWhenReady();
        while (System.currentTimeMillis() < deadline) {
            if (cancellation.isCancelled()) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareAbort",
                        "basename=" + basename + " reason=cancelled");
                return false;
            }
            PlaybackException err = player.getPlayerError();
            if (err != null) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareFail",
                        "basename=" + basename + " "
                                + exoPlayerSnapshotForSyncLog(player));
                return false;
            }
            int state = player.getPlaybackState();
            if (state == Player.STATE_READY) {
                return true;
            }
            if (!primedPlayback
                    && state == Player.STATE_BUFFERING
                    && player.getBufferedPosition() <= 0L
                    && System.currentTimeMillis() - startedAt >= 3_000L) {
                player.setPlayWhenReady(true);
                primedPlayback = true;
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrimePlaybackForBuffer",
                        "basename=" + basename + " " + exoPlayerSnapshotForSyncLog(player));
            }
            Thread.sleep(20L);
        }
        VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareTimeout",
                "basename=" + basename + " " + exoPlayerSnapshotForSyncLog(player));
        return false;
    }
}
