package ca.pkay.rcloneexplorer.Glide;

import androidx.annotation.NonNull;
import com.bumptech.glide.load.Key;
import java.security.MessageDigest;

public class VideoThumbnailUrl implements Key {
    private final String url;

    public VideoThumbnailUrl(@NonNull String url) {
        this.url = url;
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(url.getBytes(CHARSET));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoThumbnailUrl)) return false;
        return url.equals(((VideoThumbnailUrl) o).url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }
}
