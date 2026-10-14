package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import ca.pkay.rcloneexplorer.Glide.OkHttpMediaDataSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Serial muted surface-less ExoPlayer probe over rclone HTTP serve URLs.
 *
 * Threading: player lives on a dedicated HandlerThread (its application looper). Waiting happens
 * on the caller thread; never block the player looper while waiting (same contract as
 * [ca.pkay.rcloneexplorer.Glide.VideoThumbnailExoFallback]).
 *
 * Media3 1.3.1 MetadataRetriever returns track groups only (no seekability), so a single Exo
 * prepare supplies both audio-track and seek signals without a second network session.
 */
object VideoProblemProbe {

    const val PREPARE_TIMEOUT_MS = 20_000L
    private const val EXO_READ_WAIT_MS = 2_000L
    private const val RELEASE_WAIT_MS = 5_000L
    private const val SETUP_WAIT_MS = 5_000L

    private val threadLock = Any()
    @Volatile
    private var exoHandler: Handler? = null

    fun probeHttpUrl(context: Context, httpUrl: String): VideoProblemClassifier.ProbeFacts {
        val app = context.applicationContext
        val handler = ensureExoHandler()
        val playerRef = AtomicReference<ExoPlayer?>(null)
        val readyOrError = CountDownLatch(1)
        val playerError = AtomicReference<PlaybackException?>(null)
        val setupError = AtomicReference<String?>(null)
        val setupDone = CountDownLatch(1)

        val setupPosted = handler.post {
            try {
                val looper = Looper.myLooper()!!
                val renderersFactory = Media3ExtensionRenderers.newDefaultRenderersFactory(app)
                val upstreamFactory = OkHttpDataSource.Factory(OkHttpMediaDataSource.getClient())
                    .setUserAgent(Util.getUserAgent(app, "RoundSyncVideoScan"))
                val mediaSourceFactory = ProgressiveMediaSource.Factory(upstreamFactory)
                val player = ExoPlayer.Builder(app, renderersFactory)
                    .setLooper(looper)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                player.volume = 0f
                player.playWhenReady = false
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                            readyOrError.countDown()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerError.set(error)
                        readyOrError.countDown()
                    }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.parse(httpUrl)))
                playerRef.set(player)
                player.prepare()
            } catch (t: Throwable) {
                setupError.set(scrub(t.message ?: t.javaClass.simpleName))
                readyOrError.countDown()
            } finally {
                setupDone.countDown()
            }
        }
        if (!setupPosted) {
            return failed("exo_handler_post_failed")
        }
        if (!setupDone.await(SETUP_WAIT_MS, TimeUnit.MILLISECONDS)) {
            releasePlayerAsync(handler, playerRef)
            return failed("setup_timeout")
        }
        val setupErr = setupError.get()
        if (setupErr != null) {
            releasePlayerAsync(handler, playerRef)
            return failed(setupErr)
        }
        val ready = readyOrError.await(PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ready) {
            releasePlayerAsync(handler, playerRef)
            return failed("prepare_timeout")
        }
        val err = playerError.get()
        if (err != null) {
            releasePlayerAsync(handler, playerRef)
            return failed(scrub(err.message ?: err.errorCodeName))
        }

        val factsRef = AtomicReference<VideoProblemClassifier.ProbeFacts?>(null)
        val readDone = CountDownLatch(1)
        handler.post {
            try {
                val player = playerRef.get()
                if (player == null) {
                    factsRef.set(failed("player_missing"))
                } else {
                    factsRef.set(readFacts(player))
                }
            } catch (t: Throwable) {
                factsRef.set(failed(scrub(t.message ?: t.javaClass.simpleName)))
            } finally {
                readDone.countDown()
            }
        }
        if (!readDone.await(EXO_READ_WAIT_MS, TimeUnit.MILLISECONDS)) {
            releasePlayerAsync(handler, playerRef)
            return failed("read_timeout")
        }
        releasePlayerAsync(handler, playerRef)
        return factsRef.get() ?: failed("read_no_result")
    }

    private fun readFacts(player: ExoPlayer): VideoProblemClassifier.ProbeFacts {
        val audioTracks = ArrayList<VideoProblemClassifier.AudioTrackFact>()
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) {
                continue
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                audioTracks.add(
                    VideoProblemClassifier.AudioTrackFact(
                        sampleMimeType = format.sampleMimeType.orEmpty(),
                        language = format.language.orEmpty(),
                        decodable = VideoAudioDecodable.isDecodable(format),
                    ),
                )
            }
        }
        val seekable = player.isCurrentMediaItemSeekable
        val cmd = player.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_WINDOW)
        return VideoProblemClassifier.ProbeFacts(
            audioTracks = audioTracks,
            isSeekable = seekable,
            hasSeekInCurrentWindowCommand = cmd,
            probeFailed = false,
            failureDetail = "",
        )
    }

    private fun releasePlayerAsync(handler: Handler, playerRef: AtomicReference<ExoPlayer?>) {
        val releaseDone = CountDownLatch(1)
        handler.post {
            try {
                playerRef.getAndSet(null)?.release()
            } catch (_: Throwable) {
            } finally {
                releaseDone.countDown()
            }
        }
        releaseDone.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun failed(detail: String): VideoProblemClassifier.ProbeFacts =
        VideoProblemClassifier.ProbeFacts(
            audioTracks = emptyList(),
            isSeekable = false,
            hasSeekInCurrentWindowCommand = false,
            probeFailed = true,
            failureDetail = detail,
        )

    private fun ensureExoHandler(): Handler {
        synchronized(threadLock) {
            val existing = exoHandler
            if (existing != null) {
                return existing
            }
            val thread = HandlerThread("video_problem_scan_exo")
            thread.start()
            val handler = Handler(thread.looper)
            exoHandler = handler
            return handler
        }
    }

    private fun scrub(msg: String): String =
        msg.replace('\n', ' ').replace('\r', ' ').take(200)
}
