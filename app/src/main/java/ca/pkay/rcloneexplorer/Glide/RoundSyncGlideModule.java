package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
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
            // Single DiskLruCache instance shared with ThumbnailDiskCacheEvictor (see holder).
            builder.setDiskCache(GlideDiskCacheHolder.factory(thumbnailsDir));
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
        // Folder models: dedicated loader wraps stock HTTP fetch + ThumbnailImageTranscoder.
        // Registered before GlideUrl replace; HttpServeThumbnailLoader.handles excludes folders
        // so Glide never MultiModelLoader-fall-through to an untranscoded path.
        registry.replace(FolderThumbnailGlideUrl.class, InputStream.class,
                new FolderThumbnailLoader.Factory(context));
        // replace, not prepend: see HttpServeThumbnailLoader for why fall-through must be avoided.
        // Plain GlideUrl and HttpServeThumbnailGlideUrl go through HttpServeThumbnailLoader.
        registry.replace(GlideUrl.class, InputStream.class,
                new HttpServeThumbnailLoader.Factory(context));
    }
}
