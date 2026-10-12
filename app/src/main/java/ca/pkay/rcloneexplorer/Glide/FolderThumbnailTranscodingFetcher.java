package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.HttpException;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps the stock {@link GlideUrl} fetcher for {@link FolderThumbnailGlideUrl} loads and runs the
 * response through {@link ThumbnailImageTranscoder} before Glide writes the DATA disk-cache entry.
 */
public class FolderThumbnailTranscodingFetcher implements DataFetcher<InputStream> {

    private static final int READ_CHUNK_BYTES = 64 * 1024;

    private final DataFetcher<InputStream> delegate;
    @Nullable
    private volatile InputStream resultStream;

    FolderThumbnailTranscodingFetcher(@NonNull DataFetcher<InputStream> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void loadData(@NonNull Priority priority,
                         @NonNull DataCallback<? super InputStream> callback) {
        delegate.loadData(priority, new DataCallback<InputStream>() {
            @Override
            public void onDataReady(@Nullable InputStream data) {
                if (data == null) {
                    callback.onDataReady(null);
                    return;
                }
                try {
                    byte[] original = readFully(data);
                    byte[] bounded = ThumbnailImageTranscoder.transcode(original);
                    if (bounded == null) {
                        callback.onLoadFailed(new HttpException(
                                "Unsupported image payload for folder thumbnail",
                                HttpException.UNKNOWN));
                        return;
                    }
                    InputStream out = new ByteArrayInputStream(bounded);
                    resultStream = out;
                    callback.onDataReady(out);
                } catch (IOException e) {
                    callback.onLoadFailed(new HttpException(
                            "Failed to read folder thumbnail: " + e.getMessage(),
                            HttpException.UNKNOWN,
                            e));
                } finally {
                    try {
                        data.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void onLoadFailed(@NonNull Exception e) {
                callback.onLoadFailed(e);
            }
        });
    }

    @NonNull
    private static byte[] readFully(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(READ_CHUNK_BYTES);
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    @Override
    public void cleanup() {
        InputStream stream = resultStream;
        resultStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        delegate.cleanup();
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @NonNull
    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {
        return delegate.getDataSource();
    }
}
