package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory;
import com.bumptech.glide.module.AppGlideModule;

import java.io.File;
import java.io.InputStream;

import ca.pkay.rcloneexplorer.util.GlideThumbnailCacheLayout;

@GlideModule
public class RoundSyncGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        // Cache directory under filesDir so it survives Settings -> Storage -> Clear cache.
        // Glide invokes the supplier on a worker thread and calls mkdirs() on the result itself.
        File dir = GlideThumbnailCacheLayout.glideDiskCacheDir(context);
        builder.setDiskCache(new DiskLruCacheFactory(
                () -> dir,
                GlideThumbnailCacheLayout.DEFAULT_DISK_CACHE_SIZE_BYTES));
    }

    @Override
    public void registerComponents(@NonNull Context context,
                                   @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.prepend(VideoThumbnailUrl.class, InputStream.class,
                new VideoThumbnailLoader.Factory(context));
    }
}
