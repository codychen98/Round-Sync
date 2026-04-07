package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.InputStream;

public class VideoThumbnailLoader implements ModelLoader<VideoThumbnailUrl, InputStream> {

    private final Context appContext;

    public VideoThumbnailLoader(@NonNull Context appContext) {
        this.appContext = appContext;
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull VideoThumbnailUrl model,
                                                int width, int height,
                                                @NonNull Options options) {
        return new LoadData<>(
                new ObjectKey(model.getStablePath()),
                new VideoThumbnailFetcher(model.getUrl(), appContext)
        );
    }

    @Override
    public boolean handles(@NonNull VideoThumbnailUrl model) {
        return true;
    }

    public static class Factory implements ModelLoaderFactory<VideoThumbnailUrl, InputStream> {

        private final Context appContext;

        public Factory(@NonNull Context appContext) {
            this.appContext = appContext.getApplicationContext();
        }

        @NonNull
        @Override
        public ModelLoader<VideoThumbnailUrl, InputStream> build(
                @NonNull MultiModelLoaderFactory multiFactory) {
            return new VideoThumbnailLoader(appContext);
        }

        @Override
        public void teardown() {
        }
    }
}
