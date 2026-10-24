package app.prashant.castbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address

object Net {
    /**
     * The phone's IPv4 address on Wi-Fi. Not simply the active network: with mobile data on,
     * Android can report the cellular address, which the TV can't reach.
     */
    fun wifiAddress(context: Context): LinkAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return cm.getLinkProperties(wifi)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
    }

    fun wifiIp(context: Context): String? = wifiAddress(context)?.address?.hostAddress
}
