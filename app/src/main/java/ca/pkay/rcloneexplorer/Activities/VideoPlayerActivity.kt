package ca.pkay.rcloneexplorer.Activities

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ca.pkay.rcloneexplorer.R
import java.io.IOException

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        topBar = findViewById(R.id.top_bar)
        videoTitle = findViewById(R.id.video_title)
        episodeCounter = findViewById(R.id.episode_counter)
        btnScaleMode = findViewById(R.id.btn_scale_mode)

        applyScaleMode()
        topBar.visibility = View.GONE

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = visibility
            }
        )

        btnScaleMode.setOnClickListener { cycleScaleMode() }

        val uris = intent.getStringArrayExtra("video_urls") ?: return finish()
        val names = intent.getStringArrayExtra("video_names") ?: return finish()
        val startIndex = intent.getIntExtra("start_index", 0)

        initializePlayer(uris, names, startIndex)
    }

    private fun initializePlayer(uris: Array<String>, names: Array<String>, startIndex: Int) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo

            val mediaItems = uris.map { MediaItem.fromUri(it) }
            exo.setMediaItems(mediaItems, startIndex, 0L)

            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateUI(names)
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

        if (retryCount < maxRetries && (errorType == ErrorType.NETWORK || errorType == ErrorType.SEEKING || errorType == ErrorType.SERVER)) {
            retryCount++
            Toast.makeText(this, getString(R.string.video_error_retry, retryCount, maxRetries), Toast.LENGTH_SHORT).show()
            player?.prepare()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
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
        super.onDestroy()
    }
}
