package ca.pkay.rcloneexplorer.Glide;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;

import ca.pkay.rcloneexplorer.util.FLog;

public class VideoThumbnailFetcher implements DataFetcher<InputStream> {

    private static final String TAG = "VideoThumbnailFetcher";
    private final String url;
    private volatile boolean cancelled;

    public VideoThumbnailFetcher(@NonNull String url) {
        this.url = url;
    }

    @Override
    public void loadData(@NonNull Priority priority,
                         @NonNull DataCallback<? super InputStream> callback) {
        if (cancelled) {
            callback.onLoadFailed(new RuntimeException("Cancelled"));
            return;
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(url, new HashMap<>());
            Bitmap frame = extractNonBlackFrame(mmr);
            if (frame == null) {
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
            callback.onLoadFailed(e);
        } finally {
            try {
                mmr.release();
            } catch (Exception ignore) {
            }
        }
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
                    2_000_000,          // 2 seconds (in microseconds)
                    durationMs * 100,   // 10% into the video
                    durationMs * 200,   // 20% into the video
                    0                   // fallback: first frame
            };
        } else {
            timestamps = new long[]{2_000_000, 5_000_000, 0};
        }

        Bitmap bestFrame = null;
        for (long ts : timestamps) {
            if (cancelled) break;
            Bitmap frame = mmr.getFrameAtTime(ts,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) continue;
            if (!isMostlyBlack(frame)) {
                if (bestFrame != null) {
                    bestFrame.recycle();
                }
                return frame;
            }
            if (bestFrame == null) {
                bestFrame = frame;
            } else {
                frame.recycle();
            }
        }
        return bestFrame;
    }

    private static boolean isMostlyBlack(Bitmap bitmap) {
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
        double avgBrightness = (double) totalBrightness / (samples * 3);
        return avgBrightness < 10;
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void cancel() {
        cancelled = true;
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
