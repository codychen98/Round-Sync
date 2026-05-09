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

import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager;
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
 *
 * URL resolution is deferred to request time via {@link BaseUrlSupplier} so that a server
 * respawn between bind time and the first MMR read does not yield a stale-auth 401/404.
 * On a 401, 404, or 5xx response — or an {@link IOException} — the class retries once with
 * a freshly resolved URL, covering the case where the server respawned mid-fetch.
 */
public class OkHttpMediaDataSource extends MediaDataSource {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    static OkHttpClient getClient() {
        return CLIENT;
    }

    private static final int BUFFER_SIZE = 512 * 1024; // 512 KB read-ahead

    // --- Seam interfaces (package-private for unit tests) ---

    interface BaseUrlSupplier {
        @Nullable String getCurrentBaseUrl();
    }

    /** Carrier for a raw HTTP response; avoids leaking OkHttp types into tests. */
    static final class RawResponse {
        final int code;
        /** Range requests: body bytes (first {@code contentLength} bytes are valid). HEAD: null. */
        @Nullable final byte[] body;
        /** Range requests: number of valid bytes in {@code body}. HEAD: Content-Length or -1. */
        final long contentLength;

        RawResponse(int code, @Nullable byte[] body, long contentLength) {
            this.code = code;
            this.body = body;
            this.contentLength = contentLength;
        }
    }

    interface HttpCaller {
        @NonNull RawResponse range(@NonNull String resolvedUrl, long position, long endInclusive)
                throws IOException;

        @NonNull RawResponse head(@NonNull String resolvedUrl) throws IOException;
    }

    // --- Fields ---

    /** Auth-stripped stable path (shape: {@code /<remoteName>/.../<filename>}). */
    private final String stablePath;
    @Nullable
    private final Context appContext;
    private final BaseUrlSupplier baseUrlSupplier;
    private final HttpCaller httpCaller;
    private final AtomicBoolean httpDiagLogged = new AtomicBoolean(false);
    private long cachedSize = -1;

    // Read-ahead buffer — reduces HTTP round-trips for sequential MMR reads
    private byte[] readBuffer;
    private long bufferStart = -1;
    private int bufferLength = 0;

    /** Production constructor. Base URL is resolved per-request via {@link ThumbnailServerManager}. */
    public OkHttpMediaDataSource(@NonNull String stablePath, @Nullable Context appContextForDiag) {
        this(stablePath, appContextForDiag,
                () -> ThumbnailServerManager.getInstance().getCurrentBaseUrlOrNull(),
                defaultHttpCaller());
    }

    /** Package-private constructor for unit tests with injectable seams. */
    OkHttpMediaDataSource(@NonNull String stablePath, @Nullable Context appContextForDiag,
                          @NonNull BaseUrlSupplier baseUrlSupplier,
                          @NonNull HttpCaller httpCaller) {
        this.stablePath = stablePath;
        this.appContext = appContextForDiag != null ? appContextForDiag.getApplicationContext() : null;
        this.baseUrlSupplier = baseUrlSupplier;
        this.httpCaller = httpCaller;
    }

    private static HttpCaller defaultHttpCaller() {
        return new HttpCaller() {
            @Override
            public @NonNull RawResponse range(@NonNull String resolvedUrl, long position,
                                              long endInclusive) throws IOException {
                int fetchSize = (int) (endInclusive - position + 1);
                Request req = new Request.Builder()
                        .url(resolvedUrl)
                        .header("Range", "bytes=" + position + "-" + endInclusive)
                        .build();
                try (Response response = CLIENT.newCall(req).execute()) {
                    if (response.code() == 416) return new RawResponse(416, null, 0);
                    if (!response.isSuccessful()) return new RawResponse(response.code(), null, 0);
                    ResponseBody body = response.body();
                    if (body == null) return new RawResponse(response.code(), null, 0);
                    try (InputStream is = body.byteStream()) {
                        byte[] buf = new byte[fetchSize];
                        int total = 0;
                        while (total < fetchSize) {
                            int read = is.read(buf, total, fetchSize - total);
                            if (read < 0) break;
                            total += read;
                        }
                        return new RawResponse(response.code(), total > 0 ? buf : null, total);
                    }
                }
            }

            @Override
            public @NonNull RawResponse head(@NonNull String resolvedUrl) throws IOException {
                Request req = new Request.Builder().url(resolvedUrl).head().build();
                try (Response response = CLIENT.newCall(req).execute()) {
                    if (!response.isSuccessful()) return new RawResponse(response.code(), null, -1);
                    String cl = response.header("Content-Length");
                    long len = cl != null ? Long.parseLong(cl) : -1;
                    return new RawResponse(response.code(), null, len);
                }
            }
        };
    }

    @Nullable
    private String resolveUrlOrNull() {
        String base = baseUrlSupplier.getCurrentBaseUrl();
        return base == null ? null : base + stablePath;
    }

    private static int parseResolvedPort(@Nullable String resolvedUrl) {
        if (resolvedUrl == null) return -1;
        return Uri.parse(resolvedUrl).getPort();
    }

    private static String parseResolvedAuth6(@Nullable String resolvedUrl) {
        if (resolvedUrl == null) return "?";
        String path = Uri.parse(resolvedUrl).getPath();
        if (path == null || path.length() < 2) return "?";
        String noSlash = path.substring(1);
        int idx = noSlash.indexOf('/');
        String auth = idx > 0 ? noSlash.substring(0, idx) : noSlash;
        return auth.length() >= 6 ? auth.substring(0, 6) : auth;
    }

    private void logHttpIssueOnce(@NonNull String where, int code, int attempt) {
        if (appContext == null || !httpDiagLogged.compareAndSet(false, true)) {
            return;
        }
        String file = stablePath.substring(stablePath.lastIndexOf('/') + 1);
        SyncLog.error(appContext, "VidThumbDbg",
                "event=httpThumbFail where=" + where + " code=" + code
                        + " attempt=" + attempt + " file=" + file);
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

        return executeRange(position, end, fetchSize, size, buffer, offset, 1);
    }

    private int executeRange(long position, long end, int fetchSize, int copySize,
                             byte[] buffer, int offset, int attempt) throws IOException {
        String resolvedUrl = resolveUrlOrNull();
        if (resolvedUrl == null) return -1;
        VideoThumbnailFetcher.logThumbPipe(appContext, "mmrHttpResolve",
                "attempt=" + attempt
                        + " resolvedPort=" + parseResolvedPort(resolvedUrl)
                        + " resolvedAuth6=" + parseResolvedAuth6(resolvedUrl)
                        + " stable=" + stablePath);
        RawResponse resp;
        try {
            resp = httpCaller.range(resolvedUrl, position, end);
        } catch (IOException e) {
            if (attempt == 1) {
                return executeRange(position, end, fetchSize, copySize, buffer, offset, 2);
            }
            throw e;
        }
        if (resp.code == 416) return -1;
        if (resp.body == null || resp.code < 200 || resp.code >= 300) {
            logHttpIssueOnce("readAt", resp.code, attempt);
            if (attempt == 1) {
                return executeRange(position, end, fetchSize, copySize, buffer, offset, 2);
            }
            return -1;
        }
        int totalRead = (int) resp.contentLength;
        if (totalRead == 0) return -1;
        if (readBuffer == null || readBuffer.length < fetchSize) {
            readBuffer = new byte[fetchSize];
        }
        System.arraycopy(resp.body, 0, readBuffer, 0, totalRead);
        bufferStart = position;
        bufferLength = totalRead;
        int toCopy = Math.min(copySize, totalRead);
        System.arraycopy(readBuffer, 0, buffer, offset, toCopy);
        return toCopy;
    }

    @Override
    public long getSize() throws IOException {
        if (cachedSize >= 0) return cachedSize;
        return executeHead(1);
    }

    private long executeHead(int attempt) throws IOException {
        String resolvedUrl = resolveUrlOrNull();
        if (resolvedUrl == null) return -1;
        VideoThumbnailFetcher.logThumbPipe(appContext, "mmrHttpResolve",
                "attempt=" + attempt
                        + " resolvedPort=" + parseResolvedPort(resolvedUrl)
                        + " resolvedAuth6=" + parseResolvedAuth6(resolvedUrl)
                        + " stable=" + stablePath);
        RawResponse resp;
        try {
            resp = httpCaller.head(resolvedUrl);
        } catch (IOException e) {
            if (attempt == 1) {
                return executeHead(2);
            }
            throw e;
        }
        if (resp.code >= 200 && resp.code < 300 && resp.contentLength >= 0) {
            cachedSize = resp.contentLength;
            return cachedSize;
        }
        logHttpIssueOnce("headSize", resp.code, attempt);
        if (attempt == 1) {
            return executeHead(2);
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        readBuffer = null;
        bufferStart = -1;
        bufferLength = 0;
    }
}
