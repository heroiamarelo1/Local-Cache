package app.localcache.storage

import android.content.Context
import app.localcache.Prefs
import java.io.File

/** Single source of truth for where a cached movie lives on the USB drive. */
object CachePaths {

    fun cacheRoot(context: Context): File? =
        Prefs.cacheDirPath(context)?.let { File(it) }

    fun finalFile(context: Context, cacheKey: String, url: String): File? {
        val root = cacheRoot(context) ?: return null
        return File(root, safeName(cacheKey) + guessExt(url))
    }

    fun partFile(finalFile: File): File = File(finalFile.absolutePath + ".part")

    fun guessExt(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".mkv") -> ".mkv"
            lower.contains(".webm") -> ".webm"
            lower.contains(".avi") -> ".avi"
            else -> ".mp4"
        }
    }

    fun safeName(key: String): String = key.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
