package app.localcache.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UpstreamProxy {
    private const val TAG = "UpstreamProxy"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    data class ProxyResult(
        val responseStart: Long,
        val responseEnd: Long,
        val total: Long,
        val body: okhttp3.ResponseBody,
    )

    suspend fun fetchTotalBytes(url: String): Long = withContext(Dispatchers.IO) {
        fetchTotalBytesSync(url)
    }

    fun fetchTotalBytesSync(url: String): Long {
        return try {
            val head = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "LocalCache-TV/0.1")
                .build()
            client.newCall(head).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
                }
            }

            val probe = Request.Builder()
                .url(url)
                .header("User-Agent", "LocalCache-TV/0.1")
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(probe).execute().use { response ->
                val contentRange = response.header("Content-Range")
                Regex("/(\\d+)$").find(contentRange ?: "")?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "size probe failed: ${e.message}")
            0L
        }
    }

    suspend fun openRange(url: String, start: Long, end: Long, totalBytes: Long): ProxyResult =
        withContext(Dispatchers.IO) {
            openRangeSync(url, start, end, totalBytes)
        }

    fun openRangeSync(url: String, start: Long, end: Long, totalBytes: Long): ProxyResult {
        val rangeEnd = when {
            end >= 0 -> end
            totalBytes > 0 -> totalBytes - 1
            else -> -1L
        }
        val rangeHeader = if (rangeEnd >= 0) "bytes=$start-$rangeEnd" else "bytes=$start-"

        Log.i(TAG, "proxy $rangeHeader (not on disk yet)")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalCache-TV/0.1")
            .header("Range", rangeHeader)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw IllegalStateException("Upstream HTTP ${response.code}")
        }

        val body = response.body ?: run {
            response.close()
            throw IllegalStateException("Upstream empty body")
        }

        var total = totalBytes
        var responseStart = start
        var responseEnd = end

        val contentRange = response.header("Content-Range")
        if (contentRange != null) {
            val match = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").find(contentRange)
            if (match != null) {
                responseStart = match.groupValues[1].toLong()
                responseEnd = match.groupValues[2].toLong()
                if (match.groupValues[3] != "*") {
                    total = match.groupValues[3].toLong()
                }
            }
        } else if (responseEnd < 0) {
            val len = body.contentLength()
            responseEnd = if (len > 0) responseStart + len - 1 else responseStart
        }

        if (total <= 0) {
            total = responseEnd + 1
        }

        return ProxyResult(responseStart, responseEnd, total, body)
    }
}
