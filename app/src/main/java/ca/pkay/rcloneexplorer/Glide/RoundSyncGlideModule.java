package ca.pkay.rcloneexplorer.Glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

@GlideModule
public class RoundSyncGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context,
                                   @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.prepend(VideoThumbnailUrl.class, InputStream.class,
                new VideoThumbnailLoader.Factory());
    }
}
