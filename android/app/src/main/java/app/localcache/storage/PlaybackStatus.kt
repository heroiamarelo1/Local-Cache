package app.localcache.storage

import org.json.JSONObject
import java.util.Locale

/** What /settings shows for the active play session. */
object PlaybackStatus {
    const val IDLE = "idle"
    const val LOCAL = "local"
    const val STREAMING = "streaming"

    @Volatile
    private var cacheKey: String? = null

    @Volatile
    private var mode: String = IDLE

    @Volatile
    private var detail: String = ""

    @Volatile
    private var updatedAtMs: Long = 0L

    fun markLocal(key: String, detail: String) {
        cacheKey = key
        mode = LOCAL
        this.detail = detail
        updatedAtMs = System.currentTimeMillis()
    }

    fun markStreaming(key: String, detail: String) {
        cacheKey = key
        mode = STREAMING
        this.detail = detail
        updatedAtMs = System.currentTimeMillis()
    }

    fun snapshot(): JSONObject {
        val parts = DownloadEngine.statusParts()
        val key = DownloadEngine.activeKey()
        val entry = key?.let { CacheRegistry.get(it) }
        val progress = key?.let { CacheRegistry.progress(it) } ?: 0
        val playLabel = when (mode) {
            LOCAL -> "Local storage"
            STREAMING -> "Streaming (not caught up on disk yet)"
            else -> "Nothing playing"
        }
        val detailLabel = humanDetail(detail)
        return JSONObject()
            .put("ok", true)
            .put("downloadTitle", parts?.title ?: "Idle — ready for Stremio")
            .put("downloadStats", parts?.stats.orEmpty())
            .put("downloadLine", DownloadEngine.statusLine())
            .put("downloadKey", key)
            .put("downloadProgress", progress)
            .put("downloadStatus", entry?.status)
            .put("playbackMode", mode)
            .put("playbackLabel", playLabel)
            .put("playbackKey", cacheKey)
            .put("playbackDetail", detailLabel)
            .put("updatedAtMs", updatedAtMs)
    }

    private fun humanDetail(raw: String): String {
        if (raw.isBlank()) return ""
        val bytes = Regex("""serving bytes (\d+) on disk""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        if (bytes != null) {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024) {
                "%.1f GB on disk".format(Locale.US, mb / 1024.0)
            } else {
                "%.0f MB on disk".format(Locale.US, mb)
            }
        }
        return raw
    }
}
