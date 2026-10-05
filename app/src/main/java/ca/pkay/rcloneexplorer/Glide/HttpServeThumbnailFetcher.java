package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

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
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ca.pkay.rcloneexplorer.util.ThumbnailDiagLog;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads an rclone-serve image and hands Glide a bounded JPEG instead of the original bytes.
 *
 * Glide stores whatever this fetcher emits as the {@code DiskCacheStrategy.DATA} entry, so
 * shrinking here (via {@link ThumbnailImageTranscoder}) is what keeps a photo folder from
 * overflowing the thumbnail cache. Payloads the transcoder cannot recognise (SVG placeholders,
 * HTML error pages) fail the load so nothing poisonous is cached.
 */
public class HttpServeThumbnailFetcher implements DataFetcher<InputStream> {

    /** Caps once-per-URL rejection diagnostics so a bad folder cannot flood the log. */
    private static final int MAX_REJECT_LOG_URLS = 256;
    private static final Set<String> rejectLoggedPaths =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int READ_CHUNK_BYTES = 64 * 1024;

    private final HttpServeThumbnailGlideUrl model;
    private final Context appContext;
    private final long maxBytes;
    private volatile Call call;

    public HttpServeThumbnailFetcher(
            @NonNull HttpServeThumbnailGlideUrl model,
            @NonNull Context appContext,
            long maxBytes) {
        this.model = model;
        this.appContext = appContext.getApplicationContext();
        this.maxBytes = maxBytes;
    }

    @Override
    public void loadData(@NonNull Priority priority,
                         @NonNull DataCallback<? super InputStream> callback) {
        Request.Builder builder = new Request.Builder().url(model.toStringUrl());
        for (Map.Entry<String, String> header : model.getHeaders().entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }
        Call newCall = OkHttpMediaDataSource.getClient().newCall(builder.build());
        call = newCall;
        // All failures below are fetch-stage and surface as HttpException, like Glide's own
        // HttpUrlFetcher; RetryRequestListener treats any other root cause as a decode failure.
        try (Response response = newCall.execute()) {
            if (!response.isSuccessful()) {
                callback.onLoadFailed(new HttpException(
                        "HTTP " + response.code() + " for thumbnail " + stablePath(), response.code()));
                return;
            }
            ResponseBody body = response.body();
            if (body == null) {
                callback.onLoadFailed(new HttpException(
                        "Empty body for thumbnail " + stablePath(), HttpException.UNKNOWN));
                return;
            }
            byte[] original = readFully(body);
            byte[] bounded = ThumbnailImageTranscoder.transcode(original);
            if (bounded == null) {
                logRejectedOnce(response.header("Content-Type"), original);
                callback.onLoadFailed(new HttpException(
                        "Unsupported image payload for thumbnail " + stablePath(), HttpException.UNKNOWN));
                return;
            }
            callback.onDataReady(new ByteArrayInputStream(bounded));
        } catch (HttpException e) {
            callback.onLoadFailed(e);
        } catch (IOException e) {
            // Keep the cause text (e.g. "Canceled") visible: GlideException only reports leaf messages.
            callback.onLoadFailed(new HttpException(
                    "Failed to fetch thumbnail " + stablePath() + ": " + e.getMessage(),
                    HttpException.UNKNOWN, e));
        } finally {
            call = null;
        }
    }

    @NonNull
    private byte[] readFully(@NonNull ResponseBody body) throws IOException {
        long declared = body.contentLength();
        if (declared > maxBytes) {
            throw new IOException("Thumbnail source " + declared + " bytes exceeds limit " + maxBytes);
        }
        int initial = declared > 0 ? (int) Math.min(declared, Integer.MAX_VALUE - 8) : READ_CHUNK_BYTES;
        ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        long total = 0L;
        try (InputStream in = body.byteStream()) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Thumbnail source exceeds limit " + maxBytes + " bytes");
                }
                out.write(chunk, 0, read);
            }
        }
        return out.toByteArray();
    }

    /**
     * Step 7 diagnostics: records what the serve path returned for a file that was supposed to
     * be a raster image (content type, length, first 16 bytes) so the upstream cause of
     * placeholder payloads such as {@code icon-lite.svg} can be identified.
     */
    private void logRejectedOnce(@Nullable String contentType, @NonNull byte[] payload) {
        String path = stablePath();
        if (rejectLoggedPaths.size() >= MAX_REJECT_LOG_URLS && !rejectLoggedPaths.contains(path)) {
            return;
        }
        if (!rejectLoggedPaths.add(path)) {
            return;
        }
        ThumbnailDiagLog.error(
                appContext,
                "imageFetchRejected",
                "contentType=" + (contentType != null ? contentType : "?")
                        + " length=" + payload.length
                        + " head16=" + headHex(payload)
                        + " path=" + path);
    }

    @NonNull
    static String headHex(@NonNull byte[] payload) {
        int n = Math.min(16, payload.length);
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", payload[i] & 0xFF));
        }
        return sb.toString();
    }

    @NonNull
    private String stablePath() {
        return ThumbnailStablePath.canonicalFromServeUrl(model.toStringUrl());
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void cancel() {
        Call current = call;
        if (current != null) {
            current.cancel();
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
