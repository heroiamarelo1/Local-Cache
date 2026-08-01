package app.localcache.net

import android.content.Context
import app.localcache.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class LanVerifyResult(
    val ok: Boolean,
    val host: String,
    val message: String,
)

object LanReachability {
    suspend fun verify(context: Context, host: String = Prefs.lanHost(context), port: Int = Prefs.serverPort(context)): LanVerifyResult =
        withContext(Dispatchers.IO) {
            val url = "http://$host:$port/health"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    LanVerifyResult(
                        ok = true,
                        host = host,
                        message = "Verified — phone/Stremio can reach http://$host:$port",
                    )
                } else {
                    LanVerifyResult(
                        ok = false,
                        host = host,
                        message = "HTTP $code at $url — try another LAN IP",
                    )
                }
            } catch (e: Exception) {
                LanVerifyResult(
                    ok = false,
                    host = host,
                    message = e.message ?: "Not reachable — is the server running?",
                )
            }
        }
}
