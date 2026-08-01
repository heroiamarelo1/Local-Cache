package app.localcache.storage

import android.content.Context
import android.util.Log
import app.localcache.model.StreamItem
import org.json.JSONObject
import java.io.File

/**
 * What is actually on the USB drive, independent of any upstream.
 *
 * Each cached movie gets a small sidecar file recording where it came from. Without it the
 * app forgets everything on restart, and a movie already sitting on the stick becomes
 * unplayable the moment Comet or Torrentio is unreachable — which is exactly when the local
 * copy is most wanted.
 */
object LocalLibrary {
    private const val TAG = "LocalLibrary"
    private const val META_SUFFIX = ".meta.json"

    data class Item(
        val cacheKey: String,
        val url: String,
        val rawName: String,
        val type: String,
        val id: String,
        val source: String?,
        val file: File,
        val complete: Boolean,
        val downloadedBytes: Long,
        val totalBytes: Long,
    )

    fun metaFile(finalFile: File): File = File(finalFile.absolutePath + META_SUFFIX)

    /** Called when a download starts, so the file can be identified again later. */
    fun writeMeta(entry: CacheEntry) {
        val path = entry.filePath ?: return
        val meta = metaFile(File(path))
        val json = JSONObject()
            .put("cacheKey", entry.cacheKey)
            .put("url", entry.url)
            .put("rawName", entry.label)
            .put("type", entry.type ?: "movie")
            .put("id", entry.id ?: "")
            .put("source", entry.source ?: "")
            .put("totalBytes", entry.totalBytes)
            .put("savedAt", System.currentTimeMillis())

        runCatching { meta.writeText(json.toString()) }
            .onFailure { Log.w(TAG, "could not write ${meta.name}: ${it.message}") }
    }

    fun deleteMeta(finalFile: File) {
        runCatching { metaFile(finalFile).delete() }
    }

    fun scan(context: Context): List<Item> {
        val root = CachePaths.cacheRoot(context) ?: return emptyList()
        val files = root.listFiles() ?: return emptyList()

        return files.mapNotNull { file ->
            if (!file.isFile || file.name.endsWith(META_SUFFIX)) return@mapNotNull null

            val complete = !file.name.endsWith(".part")
            val finalFile = if (complete) file else File(file.absolutePath.removeSuffix(".part"))
            val meta = runCatching { JSONObject(metaFile(finalFile).readText()) }.getOrNull()
                ?: return@mapNotNull null

            val id = meta.optString("id")
            if (id.isBlank()) return@mapNotNull null

            Item(
                cacheKey = meta.optString("cacheKey"),
                url = meta.optString("url"),
                rawName = meta.optString("rawName").ifBlank { finalFile.nameWithoutExtension },
                type = meta.optString("type").ifBlank { "movie" },
                id = id,
                source = meta.optString("source").takeIf { it.isNotBlank() },
                file = finalFile,
                complete = complete,
                downloadedBytes = file.length(),
                totalBytes = meta.optLong("totalBytes").takeIf { it > 0 } ?: file.length(),
            )
        }
    }

    /** Everything on the drive belonging to one title, biggest first. */
    fun forTitle(context: Context, type: String, id: String): List<Item> =
        scan(context)
            .filter { it.id == id && it.type == type }
            .sortedWith(
                compareByDescending<Item> { it.complete }
                    .thenByDescending { it.downloadedBytes }
            )

    /**
     * Rebuilds registry entries from the drive so cached movies are playable straight after
     * a restart, before any upstream has been contacted.
     */
    fun rehydrate(context: Context) {
        val items = scan(context)
        items.forEach { item ->
            if (item.cacheKey.isBlank()) return@forEach
            CacheRegistry.registerLocal(item)
        }
        if (items.isNotEmpty()) {
            Log.i(TAG, "restored ${items.size} cached item(s) from the drive")
        }
    }

    fun toStreamItem(item: Item): StreamItem = StreamItem(
        cacheKey = item.cacheKey,
        source = item.source ?: "USB",
        label = item.rawName,
        rawName = item.rawName,
        url = item.url,
        title = item.rawName,
    )
}
