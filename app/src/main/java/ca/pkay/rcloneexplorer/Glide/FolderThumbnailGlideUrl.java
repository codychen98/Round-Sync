package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.model.GlideUrl;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Glide URL for folder preview thumbnails. HTTP URL points at a child cover file; cache key uses
 * the folder path under a folder-specific namespace so covers stay keyed by folder identity.
 */
public class FolderThumbnailGlideUrl extends GlideUrl {

    @NonNull
    private final String folderPathForCache;

    /**
     * @param coverFileHttpUrl HTTP URL of the resolved cover file (image bytes from rclone serve)
     * @param folderPathForCache explorer folder path used for the {@code thumbFolder} cache key
     */
    public FolderThumbnailGlideUrl(@NonNull String coverFileHttpUrl, @NonNull String folderPathForCache) {
        super(coverFileHttpUrl);
        this.folderPathForCache = folderPathForCache;
    }

    /**
     * Legacy single-arg constructor: cache key derived from the URL path (folder or file).
     * Prefer {@link #FolderThumbnailGlideUrl(String, String)}.
     */
    public FolderThumbnailGlideUrl(@NonNull String url) {
        this(url, deriveFolderPathFromUrl(url));
    }

    @NonNull
    public String getFolderPathForCache() {
        return folderPathForCache;
    }

    @Override
    public String getCacheKey() {
        return ReadableCacheKey.fromStablePath(folderPathForCache, "thumbFolder");
    }

    @NonNull
    private static String deriveFolderPathFromUrl(@NonNull String url) {
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            int secondSlash = path.indexOf('/', 1);
            if (secondSlash >= 0 && secondSlash + 1 < path.length()) {
                return path.substring(secondSlash);
            }
            return path;
        } catch (MalformedURLException e) {
            return url;
        }
    }
}
