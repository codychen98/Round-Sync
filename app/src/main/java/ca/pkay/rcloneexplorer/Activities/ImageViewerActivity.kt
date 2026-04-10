package ca.pkay.rcloneexplorer.Activities

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.ImageViewerNetworkPolicy
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.appbar.MaterialToolbar

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pager: ViewPager2
    private lateinit var connectivityManager: ConnectivityManager

    private var highFidelity: Boolean = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        highFidelity = ImageViewerNetworkPolicy.preferHighFidelityLoad(this)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_image_viewer)

        toolbar = findViewById(R.id.image_viewer_toolbar)
        pager = findViewById(R.id.image_viewer_pager)

        val urls = intent.getStringArrayExtra(EXTRA_IMAGE_URLS) ?: run {
            finish()
            return
        }
        val names = intent.getStringArrayExtra(EXTRA_IMAGE_NAMES) ?: run {
            finish()
            return
        }
        if (urls.isEmpty()) {
            finish()
            return
        }
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, urls.lastIndex)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = names.getOrElse(startIndex) { "" }

        pager.offscreenPageLimit = 1
        pager.adapter = ImagePagerAdapter(urls, names)
        pager.setCurrentItem(startIndex, false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                toolbar.title = names.getOrElse(position) { "" }
                toolbar.subtitle = "${position + 1} / ${urls.size}"
            }
        })
        toolbar.subtitle = "${startIndex + 1} / ${urls.size}"
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val next = ImageViewerNetworkPolicy.preferHighFidelityCapabilities(caps)
                if (next != highFidelity) {
                    highFidelity = next
                    runOnUiThread { pager.adapter?.notifyDataSetChanged() }
                }
            }
        }
        networkCallback = cb
        connectivityManager.registerDefaultNetworkCallback(cb)
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback!!)
            networkCallback = null
        }
        super.onStop()
    }

    private fun glideRequestOptions(): RequestOptions {
        val dm = resources.displayMetrics
        val base = RequestOptions()
            .fitCenter()
            .error(R.drawable.ic_file)
        return if (highFidelity) {
            val w = (dm.widthPixels * 2).coerceAtMost(4096).coerceAtLeast(1)
            val h = (dm.heightPixels * 2).coerceAtMost(4096).coerceAtLeast(1)
            base.override(w, h).priority(Priority.HIGH)
        } else {
            val w = dm.widthPixels.coerceAtMost(1600).coerceAtLeast(1)
            val h = dm.heightPixels.coerceAtMost(1600).coerceAtLeast(1)
            base.override(w, h).priority(Priority.NORMAL)
        }
    }

    private inner class ImagePagerAdapter(
        private val urls: Array<String>,
        private val names: Array<String>
    ) : RecyclerView.Adapter<ImagePagerAdapter.PageHolder>() {

        inner class PageHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_viewer_page, parent, false) as ImageView
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            Glide.with(this@ImageViewerActivity)
                .load(GlideUrl(urls[position]))
                .apply(glideRequestOptions())
                .into(holder.imageView)
            holder.imageView.contentDescription = names.getOrElse(position) { "" }
        }

        override fun getItemCount(): Int = urls.size
    }

    companion object {
        const val EXTRA_IMAGE_URLS = "image_urls"
        const val EXTRA_IMAGE_NAMES = "image_names"
        const val EXTRA_START_INDEX = "start_index"
    }
}
