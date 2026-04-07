package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.SyncLog;

public class VideoThumbnailFetcher implements DataFetcher<InputStream> {

    private static final String TAG = "VideoThumbnailFetcher";
    private static final ExecutorService VIDEO_POOL = Executors.newFixedThreadPool(2);

    private final String url;
    private final Context appContext;
    private volatile boolean cancelled;
    private volatile Future<?> pendingTask;

    public VideoThumbnailFetcher(@NonNull String url, @NonNull Context appContext) {
        this.url = url;
        this.appContext = appContext;
    }

    @Override
    public void loadData(@NonNull Priority priority,
                         @NonNull DataCallback<? super InputStream> callback) {
        if (cancelled) {
            callback.onLoadFailed(new RuntimeException("Cancelled"));
            return;
        }
        pendingTask = VIDEO_POOL.submit(() -> {
            if (cancelled) {
                callback.onLoadFailed(new RuntimeException("Cancelled"));
                return;
            }
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            OkHttpMediaDataSource dataSource = new OkHttpMediaDataSource(url);
            try {
                mmr.setDataSource(dataSource);
                Bitmap frame = extractNonBlackFrame(mmr);
                if (frame == null) {
                    SyncLog.error(appContext, "ThumbnailServer",
                            "VideoThumbnailFetcher: no frame extracted (null or all too dark). url=" + url);
                    callback.onLoadFailed(
                            new RuntimeException("No frame extracted from " + url));
                    return;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
                frame.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                frame.recycle();
                callback.onDataReady(new ByteArrayInputStream(baos.toByteArray()));
            } catch (Exception e) {
                FLog.e(TAG, "loadData: failed to extract frame from %s", url);
                SyncLog.error(appContext, "ThumbnailServer",
                        "VideoThumbnailFetcher exception: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + " | url=" + url);
                callback.onLoadFailed(e);
            } finally {
                try { mmr.release(); } catch (Exception ignore) {}
                try { dataSource.close(); } catch (Exception ignore) {}
            }
        });
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

        long[] timestamps;
        if (durationMs > 0) {
            timestamps = new long[]{
                    durationMs * 100,   // 10% into the video (microseconds)
                    durationMs * 250,   // 25% into the video
                    durationMs * 500,   // 50% into the video
                    2_000_000,          // 2 seconds
                    0                   // fallback: first frame
            };
        } else {
            timestamps = new long[]{2_000_000, 5_000_000, 0};
        }

        Bitmap brightestFrame = null;
        double brightestScore = -1;

        for (long ts : timestamps) {
            if (cancelled) break;
            Bitmap frame = mmr.getFrameAtTime(ts,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) continue;
            double brightness = averageBrightness(frame);
            if (brightness >= 30) {
                if (brightestFrame != null) brightestFrame.recycle();
                return frame;
            }
            if (brightness > brightestScore) {
                if (brightestFrame != null) brightestFrame.recycle();
                brightestFrame = frame;
                brightestScore = brightness;
            } else {
                frame.recycle();
            }
        }
        return brightestFrame;
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
        if (task != null) {
            task.cancel(true);
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
