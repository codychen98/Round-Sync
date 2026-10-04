package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver;

@GlideModule
public class RoundSyncGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        java.io.File thumbnailsDir =
                CanonicalCachePathResolver.INSTANCE.thumbnailsDirOrNull(context.getApplicationContext());
        if (thumbnailsDir != null) {
            // Must match ThumbnailDiskCacheEvictor's maxSize; both read the memoized value.
            long diskCacheSizeBytes = ThumbnailDiskCacheSize.forDir(thumbnailsDir);
            builder.setDiskCache(new DiskLruCacheFactory(
                    () -> thumbnailsDir,
                    diskCacheSizeBytes
            ));
        } else {
            builder.setDiskCache(new InternalCacheDiskCacheFactory(
                    context, ThumbnailDiskCacheSize.FLOOR_BYTES));
        }
    }

    @Override
    public void registerComponents(@NonNull Context context,
                                   @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.prepend(VideoThumbnailUrl.class, InputStream.class,
                new VideoThumbnailLoader.Factory(context));
    }
}
