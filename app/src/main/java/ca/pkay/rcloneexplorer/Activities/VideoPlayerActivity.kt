package ca.pkay.rcloneexplorer.Activities

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Services.StreamingService
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.SelectedFolderSimpleCacheProvider
import ca.pkay.rcloneexplorer.util.VideoDoubleTapHorizontalZone
import ca.pkay.rcloneexplorer.util.VideoPlayerDoubleTapZones
import java.io.IOException

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var gestureOverlay: View
    private lateinit var topBar: View
    private lateinit var videoTitle: TextView
    private lateinit var episodeCounter: TextView
    private lateinit var btnScaleMode: ImageButton
    private var player: ExoPlayer? = null
    private var scaleIndex = 0

    private val scaleModes = intArrayOf(
        -1,  // AUTO: Portrait=Fit, Landscape=Stretch
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val scaleModeLabels = arrayOf("Auto", "Fit", "Stretch", "Crop")

    private var retryCount = 0
    private val maxRetries = 3
    private val retryWindowMs = 10000L
    private var lastErrorTime = 0L

    private var controllerUiVisible = false
    private var lastDoubleTapHandledAt = 0L

    /** One `playbackReady` SyncLog line per playlist index per activity instance (avoids rebuffer spam). */
    private val playbackReadyDiagLoggedIndices = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        gestureOverlay = findViewById(R.id.video_gesture_overlay)
        topBar = findViewById(R.id.top_bar)
        videoTitle = findViewById(R.id.video_title)
        episodeCounter = findViewById(R.id.episode_counter)
        btnScaleMode = findViewById(R.id.btn_scale_mode)

        applyScaleMode()
        topBar.visibility = View.GONE

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controllerUiVisible = visibility == View.VISIBLE
                topBar.visibility = visibility
            }
        )

        gestureOverlay.post { layoutGestureOverlayBand() }
        setupGestureOverlay()

        btnScaleMode.setOnClickListener { cycleScaleMode() }

        val uris = intent.getStringArrayExtra("video_urls") ?: return finish()
        val names = intent.getStringArrayExtra("video_names") ?: return finish()
        val startIndex = intent.getIntExtra("start_index", 0)

        initializePlayer(uris, names, startIndex)
    }

    private fun initializePlayer(uris: Array<String>, names: Array<String>, startIndex: Int) {
        val cacheFlags = intent.getBooleanArrayExtra(EXTRA_VIDEO_CACHE_ENABLED)?.let { arr ->
            if (arr.size == uris.size) {
                arr
            } else {
                BooleanArray(uris.size) { i -> i < arr.size && arr[i] }
            }
        } ?: BooleanArray(uris.size) { false }

        val useDiskCache = cacheFlags.any { it }
        val simpleCache = if (useDiskCache) {
            SelectedFolderSimpleCacheProvider.getOrNull(this)
        } else {
            null
        }

        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(this, packageName ?: "RoundSync"))

        val cacheDataSourceFactory = if (simpleCache != null) {
            CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            null
        }

        val progressiveWithCache =
            cacheDataSourceFactory?.let { ProgressiveMediaSource.Factory(it) }
        val progressiveNoCache = ProgressiveMediaSource.Factory(upstreamFactory)

        val sources: List<MediaSource> = uris.indices.map { i ->
            val item = MediaItem.fromUri(uris[i])
            if (cacheFlags[i] && progressiveWithCache != null) {
                progressiveWithCache.createMediaSource(item)
            } else {
                progressiveNoCache.createMediaSource(item)
            }
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo

            exo.setMediaSources(sources, startIndex, 0L)

            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateUI(names)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        maybeLogPlaybackReadySeekDiag(exo, names)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlaybackError(error)
                }
            })

            exo.prepare()
            exo.play()
        }
        updateUI(names)
    }

    /**
     * Correlates in-app playback (scrub / seek commands) with thumbnail HTTP path: same
     * [DefaultHttpDataSource] + progressive source as [VideoThumbnailExoFallback].
     */
    private fun maybeLogPlaybackReadySeekDiag(exo: ExoPlayer, names: Array<String>) {
        val idx = exo.currentMediaItemIndex
        if (!playbackReadyDiagLoggedIndices.add(idx)) {
            return
        }
        val basename = names.getOrElse(idx) { "" }
        val seekable = exo.isCurrentMediaItemSeekable
        val dur = exo.duration
        val durStr = if (dur == C.TIME_UNSET) "unset" else dur.toString()
        val cmdSeekInWindow = exo.availableCommands.contains(Player.COMMAND_SEEK_IN_DEFAULT_WINDOW)
        val uri = exo.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty().take(400)
        val content = buildString {
            append("event=playbackReady index=").append(idx)
            append(" basename=").append(basename.replace('\n', ' ').replace('\r', ' '))
            append(" seekable=").append(seekable)
            append(" durationMs=").append(durStr)
            append(" commandSeekInDefaultWindow=").append(cmdSeekInWindow)
            append(" httpEngine=DefaultHttpDataSource ProgressiveMediaSource")
            if (uri.isNotEmpty()) {
                append(" uri=").append(uri)
            }
        }
        SyncLog.info(this, SYNC_LOG_VIDEO_PLAYBACK_TITLE, content)
    }

    private fun updateUI(names: Array<String>) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val total = p.mediaItemCount
        videoTitle.text = names.getOrElse(index) { "" }
        episodeCounter.text = "${index + 1} / $total"
    }

    private fun applyScaleMode() {
        if (scaleModes[scaleIndex] == -1) {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            playerView.resizeMode = if (isLandscape) {
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        } else {
            playerView.resizeMode = scaleModes[scaleIndex]
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyScaleMode()
        gestureOverlay.post { layoutGestureOverlayBand() }
    }

    private fun layoutGestureOverlayBand() {
        val parent = gestureOverlay.parent as? View ?: return
        val h = parent.height
        if (h <= 0) {
            return
        }
        val lp = gestureOverlay.layoutParams as FrameLayout.LayoutParams
        lp.width = FrameLayout.LayoutParams.MATCH_PARENT
        lp.height = (h * MIDDLE_BAND_HEIGHT_FRACTION).toInt()
        lp.topMargin = (h * TOP_BOTTOM_EXCLUDE_FRACTION).toInt()
        lp.leftMargin = 0
        lp.rightMargin = 0
        lp.bottomMargin = 0
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        gestureOverlay.layoutParams = lp
    }

    private fun setupGestureOverlay() {
        val detector = GestureDetectorCompat(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControllerVisibility()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val now = System.currentTimeMillis()
                    if (now - lastDoubleTapHandledAt < DOUBLE_TAP_DEBOUNCE_MS) {
                        return true
                    }
                    lastDoubleTapHandledAt = now
                    val widthPx = gestureOverlay.width.coerceAtLeast(1)
                    when (VideoPlayerDoubleTapZones.horizontalZone(e.x, widthPx)) {
                        VideoDoubleTapHorizontalZone.LEFT -> seekRelativeMs(-SEEK_STEP_MS)
                        VideoDoubleTapHorizontalZone.CENTER -> togglePlayPause()
                        VideoDoubleTapHorizontalZone.RIGHT -> seekRelativeMs(SEEK_STEP_MS)
                    }
                    return true
                }
            }
        )
        gestureOverlay.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun toggleControllerVisibility() {
        if (controllerUiVisible) {
            playerView.hideController()
        } else {
            playerView.showController()
        }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    private fun seekRelativeMs(deltaMs: Long) {
        val p = player ?: return
        val current = p.currentPosition
        val duration = p.duration
        val target = current + deltaMs
        if (duration != C.TIME_UNSET && duration > 0) {
            p.seekTo(target.coerceIn(0L, duration))
        } else {
            p.seekTo(target.coerceAtLeast(0L))
        }
    }

    private fun cycleScaleMode() {
        scaleIndex = (scaleIndex + 1) % scaleModes.size
        applyScaleMode()
        Toast.makeText(this, scaleModeLabels[scaleIndex], Toast.LENGTH_SHORT).show()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastErrorTime > retryWindowMs) {
            retryCount = 0
        }
        lastErrorTime = currentTime

        val errorType = classifyError(error)
        val message = when (errorType) {
            ErrorType.NETWORK -> getString(R.string.video_error_network)
            ErrorType.SEEKING -> getString(R.string.video_error_seeking)
            ErrorType.FORMAT -> getString(R.string.video_error_format)
            ErrorType.SERVER -> getString(R.string.video_error_server)
            ErrorType.UNKNOWN -> getString(R.string.video_error_unknown)
        }

        val willRetry = retryCount < maxRetries &&
            (errorType == ErrorType.NETWORK || errorType == ErrorType.SEEKING || errorType == ErrorType.SERVER)
        logPlaybackExceptionToSyncLog(error, errorType, willRetry)

        if (willRetry) {
            retryCount++
            Toast.makeText(this, getString(R.string.video_error_retry, retryCount, maxRetries), Toast.LENGTH_SHORT).show()
            player?.prepare()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun logPlaybackExceptionToSyncLog(
        error: PlaybackException,
        classified: ErrorType,
        willRetry: Boolean,
    ) {
        val codeName = PlaybackException.getErrorCodeName(error.errorCode)
        val uri = player?.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        val uriShort = uri.take(400)
        val chain = formatThrowableChainForSyncLog(error)
        val content = buildString {
            append("code=").append(error.errorCode)
            append(" codeName=").append(codeName)
            append(" classified=").append(classified.name)
            append(" willRetry=").append(willRetry)
            append(" retryCount=").append(retryCount)
            append(" maxRetries=").append(maxRetries)
            append(" exoMsg=").append((error.message ?: "").replace('\n', ' ').take(350))
            if (uriShort.isNotEmpty()) {
                append(" uri=").append(uriShort)
            }
            append(" causes=").append(chain)
        }
        SyncLog.info(this, SYNC_LOG_VIDEO_PLAYBACK_TITLE, content)
    }

    private fun formatThrowableChainForSyncLog(root: Throwable): String {
        val parts = ArrayList<String>(8)
        var t: Throwable? = root
        var depth = 0
        while (t != null && depth < 8) {
            val msg = (t.message ?: "").replace('\n', ' ').take(180)
            parts.add(t.javaClass.name + ":" + msg)
            t = t.cause
            depth++
        }
        return parts.joinToString(" <= ")
    }

    private fun classifyError(error: PlaybackException): ErrorType {
        val cause = error.cause
        return when {
            cause is IOException -> ErrorType.NETWORK
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> ErrorType.NETWORK
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> ErrorType.SERVER
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> ErrorType.FORMAT
            error.message?.contains("seek", ignoreCase = true) == true ||
            error.message?.contains("range", ignoreCase = true) == true -> ErrorType.SEEKING
            else -> ErrorType.UNKNOWN
        }
    }

    private enum class ErrorType {
        NETWORK, SEEKING, FORMAT, SERVER, UNKNOWN
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        stopService(Intent(this, StreamingService::class.java))
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_CACHE_ENABLED = "video_cache_enabled"

        /** Prefix for Menu → Logs filter (written to sync.log). */
        private const val SYNC_LOG_VIDEO_PLAYBACK_TITLE = "VideoPlaybackDbg"

        private const val TOP_BOTTOM_EXCLUDE_FRACTION = 0.25f
        private const val MIDDLE_BAND_HEIGHT_FRACTION = 0.5f
        private const val SEEK_STEP_MS = 10_000L
        private const val DOUBLE_TAP_DEBOUNCE_MS = 400L
    }
}
