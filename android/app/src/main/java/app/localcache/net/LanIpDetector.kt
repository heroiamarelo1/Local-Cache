package app.localcache.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

data class LanAddress(
    val address: String,
    val interfaceName: String,
    val type: String,
)

object LanIpDetector {
    private val privateV4 = Regex(
        """^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[0-1])\.)"""
    )

    fun detectAll(context: Context): List<LanAddress> {
        val found = linkedMapOf<String, LanAddress>()

        addFromConnectivity(context, found)
        addFromNetworkInterfaces(found)

        return found.values.sortedWith(
            compareBy<LanAddress> { typeRank(it.type) }
                .thenBy { it.address }
        )
    }

    fun pickBest(candidates: List<LanAddress>): LanAddress? = candidates.firstOrNull()

    fun detectBest(context: Context): LanAddress? = pickBest(detectAll(context))

    private fun typeRank(type: String): Int = when (type) {
        "ethernet" -> 0
        "wifi" -> 1
        else -> 2
    }

    private fun addFromConnectivity(context: Context, found: LinkedHashMap<String, LanAddress>) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                else -> "other"
            }

            val props = cm.getLinkProperties(network) ?: continue
            for (link: LinkAddress in props.linkAddresses) {
                val addr = link.address
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (!isPrivateIpv4(host)) continue
                found[host] = LanAddress(host, props.interfaceName ?: type, type)
            }
        }
    }

    private fun addFromNetworkInterfaces(found: LinkedHashMap<String, LanAddress>) {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return

        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            val name = intf.name ?: continue
            val type = when {
                name.startsWith("eth", ignoreCase = true) -> "ethernet"
                name.startsWith("wlan", ignoreCase = true) -> "wifi"
                else -> "other"
            }

            for (addr in intf.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                if (!isPrivateIpv4(host)) continue
                found.putIfAbsent(host, LanAddress(host, name, type))
            }
        }
    }

    private fun isPrivateIpv4(ip: String): Boolean = privateV4.containsMatchIn(ip)
}
