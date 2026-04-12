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
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ca.pkay.rcloneexplorer.util.FLog;

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
    private static final long RENDER_WAIT_MS = 12_000L;
    private static final long PIXEL_COPY_MS = 5_000L;
    private static final long MAX_DURATION_MS = 6L * 60L * 60L * 1000L;
    private static final long OUTER_WAIT_MS =
            PREPARE_TIMEOUT_MS + RENDER_WAIT_MS + PIXEL_COPY_MS + 5_000L;

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
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=apiLtN");
            return null;
        }
        if (owner.isCancelled()) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=cancelledAtExoEntry");
            return null;
        }
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS) {
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoSkip",
                    "basename=" + base + " reason=durationOutOfRange durationMs=" + durationMs);
            return null;
        }
        Handler exoH = exoThumbHandler();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<String> errorMsg = new AtomicReference<>();
        exoH.post(() -> {
            try {
                result.set(grabOnExoLooper(appContext, url, durationMs, owner));
            } catch (Exception e) {
                errorMsg.set(e.getMessage());
                FLog.w(TAG, "Exo thumbnail fallback failed: %s", e.getMessage());
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(OUTER_WAIT_MS, TimeUnit.MILLISECONDS)) {
                FLog.w(TAG, "Exo thumbnail fallback timed out waiting for exo thread");
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoOuterTimeout",
                        "basename=" + base + " waitMs=" + OUTER_WAIT_MS);
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
    private static Bitmap grabOnExoLooper(
            @NonNull Context appContext,
            @NonNull String url,
            long durationMs,
            @NonNull VideoThumbnailFetcher owner) throws Exception {
        final String base = VideoThumbnailFetcher.basenameForThumbUrl(url);
        ExoPlayer player = null;
        ImageReader reader = null;
        try {
            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(appContext)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            player = new ExoPlayer.Builder(appContext, renderersFactory)
                    .setLooper(Looper.myLooper())
                    .build();
            reader = ImageReader.newInstance(OUT_W, OUT_H, PixelFormat.RGBA_8888, 2);
            player.setVideoSurface(reader.getSurface());
            player.setPlayWhenReady(false);
            player.setVolume(0f);

            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(Util.getUserAgent(appContext, appContext.getPackageName()));
            ProgressiveMediaSource.Factory progressiveFactory =
                    new ProgressiveMediaSource.Factory(httpFactory);
            MediaItem item = MediaItem.fromUri(url);
            player.setMediaSource(progressiveFactory.createMediaSource(item));
            VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareStart",
                    "basename=" + base + " durationMs=" + durationMs + " "
                            + thumbUrlForSyncLog(url)
                            + " httpEngine=DefaultHttpDataSource ProgressiveMediaSource"
                            + " extensionRendererMode=on");
            player.prepare();

            if (!waitForReady(appContext, base, player, owner)) {
                return null;
            }
            long seekMs = Math.max(1L, durationMs / 4);
            CountDownLatch rendered = new CountDownLatch(1);
            Player.Listener listener = new Player.Listener() {
                @Override
                public void onRenderedFirstFrame() {
                    rendered.countDown();
                }
            };
            player.addListener(listener);
            player.seekTo(seekMs);
            boolean gotFrame = rendered.await(RENDER_WAIT_MS, TimeUnit.MILLISECONDS);
            player.removeListener(listener);
            if (!gotFrame || owner.isCancelled()) {
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoNoRenderedFrame",
                        "basename=" + base + " seekMs=" + seekMs + " gotFrame=" + gotFrame
                                + " cancelled=" + owner.isCancelled()
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
            if (!copied.await(PIXEL_COPY_MS, TimeUnit.MILLISECONDS) || owner.isCancelled()) {
                bitmap.recycle();
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoPixelCopyTimeout",
                        "basename=" + base + " cancelled=" + owner.isCancelled());
                return null;
            }
            if (copyResult.get() != PixelCopy.SUCCESS) {
                FLog.w(TAG, "PixelCopy failed code=%s", copyResult.get());
                VideoThumbnailFetcher.logThumbPipe(appContext, "exoPixelCopyFail",
                        "basename=" + base + " code=" + copyResult.get());
                bitmap.recycle();
                return null;
            }
            return bitmap;
        } finally {
            if (player != null) {
                player.release();
            }
            if (reader != null) {
                reader.close();
            }
        }
    }

    private static boolean waitForReady(
            @NonNull Context appContext,
            @NonNull String basename,
            @NonNull ExoPlayer player,
            @NonNull VideoThumbnailFetcher owner)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (owner.isCancelled()) {
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
            Thread.sleep(20L);
        }
        VideoThumbnailFetcher.logThumbPipe(appContext, "exoPrepareTimeout",
                "basename=" + basename + " " + exoPlayerSnapshotForSyncLog(player));
        return false;
    }
}
