package app.localcache.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PublicIpDetector {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val endpoints = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://icanhazip.com",
    )

    /**
     * Returns the TV's public IPv4 as seen from the internet, or null on failure.
     * Needed because config.wuplay.app cannot Preview LAN add-on URLs.
     */
    fun detect(): String? {
        for (url in endpoints) {
            val body = runCatching {
                client.newCall(Request.Builder().url(url).get().build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.string()?.trim()
                    }
            }.getOrNull() ?: continue

            val ip = body.lineSequence().firstOrNull()?.trim().orEmpty()
            if (IPV4.matches(ip)) return ip
        }
        return null
    }

    private val IPV4 = Regex(
        """^(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)$""",
    )
}
