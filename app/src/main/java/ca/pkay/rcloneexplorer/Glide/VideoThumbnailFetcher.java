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
            Bitmap frame = mmr.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
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
