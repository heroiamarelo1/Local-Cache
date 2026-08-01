package app.localcache.storage

import app.localcache.model.StreamItem
import app.localcache.stream.StreamLabelFormatter
import java.util.concurrent.ConcurrentHashMap

data class CacheEntry(
    val cacheKey: String,
    val url: String,
    val label: String,
    val type: String?,
    val id: String?,
    val source: String?,
    var status: String = "registered",
    var downloadedBytes: Long = 0,
    var totalBytes: Long = 0,
    var filePath: String? = null,
    var lastError: String? = null,
    var lastAccessMs: Long = 0,
    /** Measured download throughput, so we can tell a slow link from a slow drive. */
    var bytesPerSec: Long = 0,
)

object CacheRegistry {
    private val entries = ConcurrentHashMap<String, CacheEntry>()

    fun register(stream: StreamItem, type: String, id: String) {
        val release = StreamLabelFormatter.releaseName(stream).ifBlank { stream.label }
        val existing = entries.putIfAbsent(
            stream.cacheKey,
            CacheEntry(
                cacheKey = stream.cacheKey,
                url = stream.url,
                label = release,
                type = type,
                id = id,
                source = stream.source,
            ),
        )
        // Upgrade a short "Torrentio 4K" label if we now know the real file name.
        if (existing != null && release.length > existing.label.length + 8) {
            entries[stream.cacheKey] = existing.copy(label = release)
        }
    }

    /** Recreates an entry from a file found on the drive after a restart. */
    fun registerLocal(item: LocalLibrary.Item) {
        val entry = entries.getOrPut(item.cacheKey) {
            CacheEntry(
                cacheKey = item.cacheKey,
                url = item.url,
                label = item.rawName,
                type = item.type,
                id = item.id,
                source = item.source,
            )
        }
        entry.filePath = item.file.absolutePath
        entry.downloadedBytes = item.downloadedBytes
        if (item.totalBytes > entry.totalBytes) entry.totalBytes = item.totalBytes
        if (item.complete) entry.status = "complete"
    }

    fun get(cacheKey: String): CacheEntry? = entries[cacheKey]

    fun all(): List<CacheEntry> = entries.values.toList()

    /** Marks a stream as in use, so the quota cleanup will not delete it mid-playback. */
    fun markAccessed(cacheKey: String) {
        entries[cacheKey]?.lastAccessMs = System.currentTimeMillis()
    }

    /** The file was deleted to make room — reset so it can be downloaded again later. */
    fun markEvicted(cacheKey: String) {
        val entry = entries[cacheKey] ?: return
        entry.status = "registered"
        entry.downloadedBytes = 0
        entry.lastError = null
    }

    /**
     * Point the entry at its file on the USB drive and pick up anything already downloaded.
     * Needed after an app restart, when the registry is empty but the movie is on the drive.
     */
    fun attachCachePath(context: android.content.Context, cacheKey: String) {
        val entry = get(cacheKey) ?: return
        if (entry.filePath == null) {
            entry.filePath = CachePaths.finalFile(context, cacheKey, entry.url)?.absolutePath
        }
        refreshFromDisk(cacheKey)
    }

    fun refreshFromDisk(cacheKey: String) {
        val entry = get(cacheKey) ?: return
        val fp = entry.filePath ?: return
        val final = java.io.File(fp)
        val part = java.io.File("$fp.part")
        when {
            final.exists() -> {
                entry.status = "complete"
                entry.downloadedBytes = final.length()
                entry.totalBytes = maxOf(entry.totalBytes, final.length())
            }
            part.exists() -> {
                entry.downloadedBytes = part.length()
            }
        }
    }

    fun progress(cacheKey: String): Int {
        val e = entries[cacheKey] ?: return 0
        if (e.status == "complete") return 100
        if (e.downloadedBytes <= 0) return 0
        if (e.totalBytes <= 0) return maxOf(1, (e.downloadedBytes / (50L * 1024 * 1024)).toInt())
        return minOf(99, ((e.downloadedBytes * 100) / e.totalBytes).toInt()).coerceAtLeast(1)
    }

    fun snapshot(): List<Map<String, Any?>> =
        entries.values.map { e ->
            mapOf(
                "key" to e.cacheKey,
                "status" to e.status,
                "progress" to progress(e.cacheKey),
                "downloadedBytes" to e.downloadedBytes,
                "totalBytes" to e.totalBytes,
                "bytesPerSec" to e.bytesPerSec,
                "mbitPerSec" to "%.1f".format(e.bytesPerSec * 8.0 / 1_000_000),
                "filePath" to e.filePath,
                "error" to e.lastError,
            )
        }
}
