package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.Key;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Glide model for HTTP-served video frames. The full URL includes localhost port and auth segment;
 * {@link #updateDiskCacheKey} and equality use {@link #stablePath} only (path after the first URL
 * path segment), so changing port or re-encoding the hidden prefix does not change the disk cache
 * identity for the same remote file (G1).
 */
public class VideoThumbnailUrl implements Key {
    private final String url;
    private final String stablePath;

    public VideoThumbnailUrl(@NonNull String url) {
        this.url = url;
        this.stablePath = extractStablePath(url);
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
