package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelCache;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader;

import java.io.InputStream;

import ca.pkay.rcloneexplorer.R;

/**
 * Routes {@link HttpServeThumbnailGlideUrl} loads through {@link HttpServeThumbnailFetcher} and
 * delegates every other {@link GlideUrl} (folder previews, full-size viewer loads) to Glide's
 * stock {@link HttpGlideUrlLoader}.
 *
 * Registered with {@code registry.replace} rather than {@code prepend}: Glide combines all
 * loaders assignable from the model class into a {@code MultiModelLoader} that falls through to
 * the next fetcher on failure, which would re-download and cache the very payloads the
 * transcoder rejected.
 */
public class HttpServeThumbnailLoader implements ModelLoader<GlideUrl, InputStream> {

    private final Context appContext;
    private final HttpGlideUrlLoader delegate;

    HttpServeThumbnailLoader(@NonNull Context appContext, @NonNull HttpGlideUrlLoader delegate) {
        this.appContext = appContext;
        this.delegate = delegate;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull GlideUrl model,
                                                int width, int height,
                                                @NonNull Options options) {
        if (model instanceof HttpServeThumbnailGlideUrl) {
            HttpServeThumbnailGlideUrl thumbModel = (HttpServeThumbnailGlideUrl) model;
            // Key is the model itself: disk keys stay identical to the previous HttpUrlFetcher path.
            return new LoadData<>(
                    thumbModel,
                    new HttpServeThumbnailFetcher(thumbModel, appContext, sourceSizeLimitBytes(appContext)));
        }
        return delegate.buildLoadData(model, width, height, options);
    }

    @Override
    public boolean handles(@NonNull GlideUrl model) {
        return true;
    }

    /** User-configured thumbnail source size limit (same preference the explorer applies). */
    static long sourceSizeLimitBytes(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getLong(
                context.getString(R.string.pref_key_thumbnail_size_limit),
                context.getResources().getInteger(R.integer.default_thumbnail_size_limit));
    }

    public static class Factory implements ModelLoaderFactory<GlideUrl, InputStream> {

        private final Context appContext;
        private final ModelCache<GlideUrl, GlideUrl> modelCache = new ModelCache<>(500);

        public Factory(@NonNull Context appContext) {
            this.appContext = appContext.getApplicationContext();
        }

        @NonNull
        @Override
        public ModelLoader<GlideUrl, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new HttpServeThumbnailLoader(appContext, new HttpGlideUrlLoader(modelCache));
        }

        @Override
        public void teardown() {
        }
    }
}
