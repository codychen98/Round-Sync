package ca.pkay.rcloneexplorer.Glide;

import com.bumptech.glide.load.model.GlideUrl;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Glide URL for rclone HTTP-serve thumbnails; disk cache key ignores the random auth segment
 * so thumbnails survive per-session auth rotation.
 */
public class HttpServeThumbnailGlideUrl extends GlideUrl {

    public HttpServeThumbnailGlideUrl(String url) {
        super(url);
    }

    @Override
    public String getCacheKey() {
        try {
            URL url = super.toURL();
            String path = url.getPath();
            return path.substring(path.indexOf('/', 1));
        } catch (MalformedURLException e) {
            return super.getCacheKey();
        }
    }
}
