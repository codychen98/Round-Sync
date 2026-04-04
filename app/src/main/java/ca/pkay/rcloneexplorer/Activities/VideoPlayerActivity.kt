package ca.pkay.rcloneexplorer.Activities

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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ca.pkay.rcloneexplorer.R

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var videoTitle: TextView
    private lateinit var episodeCounter: TextView
    private lateinit var btnScaleMode: ImageButton
    private var player: ExoPlayer? = null
    private var scaleIndex = 1

    private val scaleModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val scaleModeLabels = arrayOf("Fit", "Stretch", "Crop")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()
        setContentView(R.layout.activity_video_player)

        playerView = findViewById(R.id.player_view)
        topBar = findViewById(R.id.top_bar)
        videoTitle = findViewById(R.id.video_title)
        episodeCounter = findViewById(R.id.episode_counter)
        btnScaleMode = findViewById(R.id.btn_scale_mode)

        playerView.resizeMode = scaleModes[scaleIndex]
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

    private fun cycleScaleMode() {
        scaleIndex = (scaleIndex + 1) % scaleModes.size
        playerView.resizeMode = scaleModes[scaleIndex]
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
