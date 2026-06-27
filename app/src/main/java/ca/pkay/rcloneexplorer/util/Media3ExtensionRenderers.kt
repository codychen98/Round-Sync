package ca.pkay.rcloneexplorer.util

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * Shared [DefaultRenderersFactory] for Media3 playback paths that must match (inline video and
 * thumbnail Exo fallback), including FFmpeg/extension decoders from the Jellyfin Media3 bundle.
 */
object Media3ExtensionRenderers {

    @JvmStatic
    fun newDefaultRenderersFactory(context: Context): DefaultRenderersFactory {
        return DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    }

    /** AV1 thumbnail/reload: prefer FFmpeg extension decoders (matches in-app playback reliability). */
    @JvmStatic
    fun newAv1PreferRenderersFactory(context: Context): DefaultRenderersFactory {
        return DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    }
}
