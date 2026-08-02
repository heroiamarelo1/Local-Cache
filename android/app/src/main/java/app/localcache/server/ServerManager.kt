package app.localcache.server

import android.content.Context
import android.util.Log
import app.localcache.Prefs
import java.io.IOException

/** One HTTP server for the whole app — picks a free port if needed. */
object ServerManager {
    private const val TAG = "ServerManager"
    private const val STOP_WAIT_MS = 800L

    @Volatile
    private var server: LocalHttpServer? = null

    data class StartResult(
        val server: LocalHttpServer,
        val port: Int,
        val portNote: String?,
    )

    @Synchronized
    @Throws(IOException::class)
    fun start(context: Context): StartResult {
        stopQuietly()
        Thread.sleep(STOP_WAIT_MS)

        val lanHost = Prefs.lanHost(context)
        val portsToTry = buildList {
            add(Prefs.serverPort(context))
            addAll(PortFinder.candidates)
        }.distinct()

        var lastError: IOException? = null

        for (port in portsToTry) {
            var instance: LocalHttpServer? = null
            try {
                instance = LocalHttpServer(context.applicationContext, lanHost, port)
                instance.startServer()
                server = instance
                Prefs.setServerPort(context, port)

                val note = if (port != PortFinder.candidates.first()) {
                    "Using port $port (${app.localcache.AppVariant.defaultPort} not available on this TV)"
                } else {
                    null
                }

                Log.i(TAG, "Started on http://$lanHost:$port")
                return StartResult(instance, port, note)
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "Port $port failed: ${e.message}")
                try {
                    instance?.stop()
                } catch (_: Exception) {
                }
                stopQuietly()
                Thread.sleep(200)
            }
        }

        throw lastError ?: IOException("Could not bind any port")
    }

    @Synchronized
    fun stop() {
        stopQuietly()
    }

    @Synchronized
    fun isRunning(): Boolean {
        val active = server?.isAlive == true
        if (!active) server = null
        return active
    }

    fun activePort(): Int? = server?.listeningPort

    private fun stopQuietly() {
        val current = server
        server = null
        if (current == null) return
        try {
            current.stop()
            Log.i(TAG, "Stopped previous server")
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed: ${e.message}")
        }
    }
}
