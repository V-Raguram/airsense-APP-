package com.airsense.companion.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import javax.net.SocketFactory

class LocalNetwork(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Prefer the hotspot even when Android keeps mobile data as its default route. */
    fun socketFactory(): SocketFactory {
        val wifi = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi?.socketFactory ?: connectivity.activeNetwork?.socketFactory ?: SocketFactory.getDefault()
    }
}
