package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null;
        }
        if (owner.isCancelled()) {
            return null;
        }
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS) {
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
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (errorMsg.get() != null) {
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
            player.prepare();

            if (!waitForReady(player, owner)) {
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
                return null;
            }
            if (copyResult.get() != PixelCopy.SUCCESS) {
                FLog.w(TAG, "PixelCopy failed code=%s", copyResult.get());
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

    private static boolean waitForReady(@NonNull ExoPlayer player, @NonNull VideoThumbnailFetcher owner)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + PREPARE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (owner.isCancelled()) {
                return false;
            }
            PlaybackException err = player.getPlayerError();
            if (err != null) {
                return false;
            }
            int state = player.getPlaybackState();
            if (state == Player.STATE_READY) {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
    }
}
