package ca.pkay.rcloneexplorer.util

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Whether an audio [Format] is decodable with the same renderer config as playback
 * (platform MediaCodec and/or Jellyfin FFmpeg extension with EXTENSION_RENDERER_MODE_ON).
 */
object VideoAudioDecodable {

    @JvmStatic
    fun isDecodable(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        if (!MimeTypes.isAudio(mime)) {
            return false
        }
        if (isMediaCodecDecodable(mime)) {
            return true
        }
        return try {
            FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isMediaCodecDecodable(mime: String): Boolean {
        return try {
            val infos = MediaCodecUtil.getDecoderInfos(mime, /* secure= */ false, /* tunneling= */ false)
            infos.isNotEmpty()
        } catch (_: MediaCodecUtil.DecoderQueryException) {
            false
        } catch (_: Throwable) {
            false
        }
    }
}
