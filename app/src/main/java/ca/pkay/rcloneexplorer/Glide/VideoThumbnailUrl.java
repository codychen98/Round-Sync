package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.load.Key;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Glide model for HTTP-served video frames. The full URL includes localhost port and auth segment;
 * {@link #updateDiskCacheKey} and equality use {@link #stablePath} only (path after the first URL
 * path segment), so changing port or re-encoding the hidden prefix does not change the disk cache
 * identity for the same remote file (G1).
 */
public class VideoThumbnailUrl implements Key {

    /**
     * Per-session sidecar: url -> [sizeBytes, mtimeEpochMs]. Populated by the 3-arg constructor
     * when callers provide file metadata, so the fetcher can build a precise {@link
     * ca.pkay.rcloneexplorer.util.BlacklistKey} without requiring a loader-layer change.
     * Bounded to prevent unbounded growth across a long session.
     */
    private static final int MAX_SIDECAR_ENTRIES = 2000;
    private static final ConcurrentHashMap<String, long[]> URL_METADATA_SIDECAR =
            new ConcurrentHashMap<>();

    private final String url;
    private final String stablePath;
    /** File size in bytes; 0 when unknown (1-arg constructor). Carried to the fetcher directly. */
    private final long sizeBytes;
    /** Modification time epoch-ms; 0 when unknown (1-arg constructor). */
    private final long mtimeEpochMs;

    public VideoThumbnailUrl(@NonNull String url) {
        this.url = url;
        this.stablePath = extractStablePath(url);
        this.sizeBytes = 0L;
        this.mtimeEpochMs = 0L;
    }

    /**
     * Constructor that carries file metadata so the fetcher can build a precise
     * {@link ca.pkay.rcloneexplorer.util.BlacklistKey} and supply an accurate file size to the
     * B2 sparse-download fallback without relying on the static sidecar map.
     * Cache identity is unaffected: {@link #updateDiskCacheKey}, {@link #equals}, and
     * {@link #hashCode} still use {@link #stablePath} only.
     */
    public VideoThumbnailUrl(@NonNull String url, long sizeBytes, long mtimeEpochMs) {
        this.url = url;
        this.stablePath = extractStablePath(url);
        this.sizeBytes = sizeBytes;
        this.mtimeEpochMs = mtimeEpochMs;
        if ((sizeBytes != 0L || mtimeEpochMs != 0L)
                && URL_METADATA_SIDECAR.size() < MAX_SIDECAR_ENTRIES) {
            URL_METADATA_SIDECAR.put(url, new long[]{sizeBytes, mtimeEpochMs});
        }
    }

    /** File size in bytes as provided by the source {@link ca.pkay.rcloneexplorer.Items.FileItem}; 0 when unknown. */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** Modification time epoch-ms as provided by the source {@link ca.pkay.rcloneexplorer.Items.FileItem}; 0 when unknown. */
    public long getMtimeEpochMs() {
        return mtimeEpochMs;
    }

    /** Returns the stable path (strips auth-token prefix) for use by the fetcher. */
    @NonNull
    public static String stablePathFor(@NonNull String url) {
        return extractStablePath(url);
    }

    /**
     * Returns the [sizeBytes, mtimeEpochMs] pair registered via the 3-arg constructor, or
     * null if metadata was not provided for this URL.
     */
    @Nullable
    public static long[] getMetadata(@NonNull String url) {
        return URL_METADATA_SIDECAR.get(url);
    }

    private static String extractStablePath(String url) {
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            int secondSlash = path.indexOf('/', 1);
            if (secondSlash > 0) {
                return path.substring(secondSlash);
            }
            return path;
        } catch (MalformedURLException e) {
            return url;
        }
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    @NonNull
    public String getStablePath() {
        return stablePath;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(stablePath.getBytes(CHARSET));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoThumbnailUrl)) return false;
        return stablePath.equals(((VideoThumbnailUrl) o).stablePath);
    }

    @Override
    public int hashCode() {
        return stablePath.hashCode();
    }
}
