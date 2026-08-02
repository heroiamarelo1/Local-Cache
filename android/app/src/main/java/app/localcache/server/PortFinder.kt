package app.localcache.server

import app.localcache.BuildConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

object PortFinder {
    /**
     * Stremio: 7100+ (keeps clear of WuPlay 7000/7001).
     * WuPlay: 7001+ (keeps clear of old 7000 cache and Stremio 7100).
     */
    val candidates: List<Int>
        get() = if (BuildConfig.WUPLAY_MODE) {
            listOf(7001, 7002, 7011, 7020, 8765, 11470, 8090)
        } else {
            listOf(7100, 7101, 7110, 8766, 11471, 8091, 8889)
        }

    fun isFree(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    fun firstFree(preferred: Int? = null): Int? {
        val order = buildList {
            if (preferred != null) add(preferred)
            addAll(candidates)
        }.distinct()
        return order.firstOrNull { isFree(it) }
    }
}
