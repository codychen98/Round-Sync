package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Decodes how aggressively the in-app image viewer should load bitmaps from the network-backed
 * stream. Loopback URLs still reflect real upstream (rclone) cost; we key off the active network.
 */
object ImageViewerNetworkPolicy {

    fun preferHighFidelityLoad(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return preferHighFidelityCapabilities(caps)
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return false
        if (!info.isConnected) {
            return false
        }
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_ETHERNET -> true
            else -> false
        }
    }

    fun preferHighFidelityCapabilities(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return true
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return true
        }
        return false
    }
}
