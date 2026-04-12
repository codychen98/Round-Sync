package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.media.MediaDataSource;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.pkay.rcloneexplorer.util.SyncLog;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MediaDataSource backed by OkHttp range requests.
 *
 * Replaces MediaMetadataRetriever's internal HTTP client, which hangs
 * on rclone serve endpoints. OkHttp handles range requests correctly
 * and is proven to work with rclone serve (used by readiness/health checks).
 *
 * Includes a read-ahead buffer to reduce HTTP round-trips: MMR makes many
 * small reads, and fetching 512 KB per request amortizes the latency.
 */
public class OkHttpMediaDataSource extends MediaDataSource {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final int BUFFER_SIZE = 512 * 1024; // 512 KB read-ahead

    private final String url;
    @Nullable
    private final Context appContext;
    private final AtomicBoolean httpDiagLogged = new AtomicBoolean(false);
    private long cachedSize = -1;

    // Read-ahead buffer — reduces HTTP round-trips for sequential reads
    private byte[] readBuffer;
    private long bufferStart = -1;
    private int bufferLength = 0;

    public OkHttpMediaDataSource(@NonNull String url, @Nullable Context appContextForDiag) {
        this.url = url;
        this.appContext = appContextForDiag != null ? appContextForDiag.getApplicationContext() : null;
    }

    private void logHttpIssueOnce(@NonNull String where, int code, @Nullable String msg) {
        if (appContext == null || !httpDiagLogged.compareAndSet(false, true)) {
            return;
        }
        String file = Uri.parse(url).getLastPathSegment();
        if (file == null) {
            file = "(unknown)";
        }
        SyncLog.error(appContext, "VidThumbDbg",
                "event=httpThumbFail where=" + where + " code=" + code + " file=" + file
                        + " msg=" + (msg != null ? msg : ""));
    }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException {
        if (size == 0) return 0;

        // Serve from buffer if possible
        if (readBuffer != null
                && position >= bufferStart
                && position + size <= bufferStart + bufferLength) {
            int bufOffset = (int) (position - bufferStart);
            System.arraycopy(readBuffer, bufOffset, buffer, offset, size);
            return size;
        }

        // Fetch new chunk with read-ahead
        int fetchSize = Math.max(size, BUFFER_SIZE);
        long end = position + fetchSize - 1;

        // Clamp to file size if known
        if (cachedSize > 0 && end >= cachedSize) {
            end = cachedSize - 1;
            fetchSize = (int) (end - position + 1);
            if (fetchSize <= 0) return -1;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Range", "bytes=" + position + "-" + end)
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (response.code() == 416) {
                return -1; // Range not satisfiable
            }
            if (!response.isSuccessful()) {
                logHttpIssueOnce("readAt", response.code(), response.message());
                return -1;
            }

            ResponseBody body = response.body();
            if (body == null) {
                logHttpIssueOnce("readAt", response.code(), "null body");
                return -1;
            }

            try (InputStream is = body.byteStream()) {
                if (readBuffer == null || readBuffer.length < fetchSize) {
                    readBuffer = new byte[fetchSize];
                }
                int totalRead = 0;
                while (totalRead < fetchSize) {
                    int read = is.read(readBuffer, totalRead, fetchSize - totalRead);
                    if (read < 0) break;
                    totalRead += read;
                }
                if (totalRead == 0) return -1;

                bufferStart = position;
                bufferLength = totalRead;

                int toCopy = Math.min(size, totalRead);
                System.arraycopy(readBuffer, 0, buffer, offset, toCopy);
                return toCopy;
            }
        }
    }

    @Override
    public long getSize() throws IOException {
        if (cachedSize >= 0) return cachedSize;

        Request request = new Request.Builder()
                .url(url)
                .head()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logHttpIssueOnce("headSize", response.code(), response.message());
                return -1;
            }
            String contentLength = response.header("Content-Length");
            if (contentLength != null) {
                cachedSize = Long.parseLong(contentLength);
                return cachedSize;
            }
        }
        return -1; // Unknown size
    }

    @Override
    public void close() throws IOException {
        readBuffer = null;
        bufferStart = -1;
        bufferLength = 0;
    }
}
