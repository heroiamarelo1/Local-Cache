package app.localcache.server

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

object PortFinder {
    /** Kept clear of the WuPlay Local Cache ports (7000/7001/…). */
    val candidates = listOf(7100, 7101, 7110, 8766, 11471, 8091, 8889)

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
