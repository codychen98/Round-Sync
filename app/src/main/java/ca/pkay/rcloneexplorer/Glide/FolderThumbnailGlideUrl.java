package ca.pkay.rcloneexplorer.Glide;

import com.bumptech.glide.load.model.GlideUrl;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Glide URL for folder preview thumbnails. Cache key uses the stable path and
 * a folder-specific namespace prefix to avoid collisions with file thumbnails.
 */
public class FolderThumbnailGlideUrl extends GlideUrl {

    public FolderThumbnailGlideUrl(String url) {
        super(url);
    }

    @Override
    public String getCacheKey() {
        try {
            URL url = super.toURL();
            String path = url.getPath();
            String stablePath = path.substring(path.indexOf('/', 1));
            return ReadableCacheKey.fromStablePath(stablePath, "thumbFolder");
        } catch (MalformedURLException e) {
            return ReadableCacheKey.fromStablePath(super.getCacheKey(), "thumbFolder");
        }
    }
}
