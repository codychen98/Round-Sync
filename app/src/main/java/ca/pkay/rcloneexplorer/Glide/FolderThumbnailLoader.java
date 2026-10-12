package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelCache;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader;

import java.io.InputStream;

/**
 * Loads {@link FolderThumbnailGlideUrl} via the stock HTTP fetcher, then transcodes bytes before
 * Glide caches them. Registered with {@code registry.replace} for this exact model class;
 * {@link HttpServeThumbnailLoader#handles} returns false for folder models so Glide does not
 * combine both loaders into a fall-through {@code MultiModelLoader}.
 */
public class FolderThumbnailLoader implements ModelLoader<FolderThumbnailGlideUrl, InputStream> {

    private final HttpGlideUrlLoader delegate;

    FolderThumbnailLoader(@NonNull HttpGlideUrlLoader delegate) {
        this.delegate = delegate;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull FolderThumbnailGlideUrl model,
                                               int width, int height,
                                               @NonNull Options options) {
        LoadData<InputStream> stock = delegate.buildLoadData(model, width, height, options);
        if (stock == null) {
            return null;
        }
        return new LoadData<>(stock.sourceKey, new FolderThumbnailTranscodingFetcher(stock.fetcher));
    }

    @Override
    public boolean handles(@NonNull FolderThumbnailGlideUrl model) {
        return true;
    }

    public static class Factory implements ModelLoaderFactory<FolderThumbnailGlideUrl, InputStream> {

        private final ModelCache<GlideUrl, GlideUrl> modelCache = new ModelCache<>(500);

        @SuppressWarnings("unused")
        public Factory(@NonNull Context context) {
        }

        @NonNull
        @Override
        public ModelLoader<FolderThumbnailGlideUrl, InputStream> build(
                @NonNull MultiModelLoaderFactory multiFactory) {
            return new FolderThumbnailLoader(new HttpGlideUrlLoader(modelCache));
        }

        @Override
        public void teardown() {
        }
    }
}
