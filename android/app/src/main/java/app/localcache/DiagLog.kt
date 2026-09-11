package app.localcache

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * In-memory + on-disk log the viewer can copy from `/logs` on their phone.
 *
 * Off by default. When the settings toggle is on, torrent/download/playback events
 * are kept so a stuck 0% download can be diagnosed without adb.
 */
object DiagLog {
    private const val TAG = "LocalCacheDiag"
    private const val MAX_LINES = 900
    private const val MAX_FILE_BYTES = 512 * 1024L

    @Volatile
    var enabled: Boolean = false
        private set

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private var file: File? = null
    private var header: String = ""

    fun attach(context: Context) {
        enabled = Prefs.debugLog(context)
        header = buildString {
            append("v").append(BuildConfig.VERSION_NAME)
            append(" sdk=").append(Build.VERSION.SDK_INT)
            append(" ").append(Build.MANUFACTURER)
            append(" ").append(Build.MODEL)
        }
        val cache = Prefs.cacheDirPath(context)?.let { File(it) }
        file = when {
            cache != null && (cache.isDirectory || cache.mkdirs()) -> File(cache, "local-cache-debug.log")
            else -> File(context.filesDir, "local-cache-debug.log")
        }
        if (enabled) i("debug log attached $header")
    }

    fun setEnabled(context: Context, on: Boolean) {
        val was = enabled
        Prefs.setDebugLog(context, on)
        if (on && !was) {
            clear()
            enabled = true
            attach(context)
            i("debug log turned ON")
        } else if (!on && was) {
            i("debug log turned OFF")
            enabled = false
        } else {
            enabled = on
            if (on) attach(context)
        }
    }

    fun i(msg: String) = add("I", msg)

    fun w(msg: String) = add("W", msg)

    fun e(msg: String) = add("E", msg)

    /** Torrent/engine detail — stored only when the settings toggle is on. */
    fun t(msg: String) {
        if (enabled) add("T", msg)
    }

    fun dump(): String = synchronized(lock) {
        buildString {
            appendLine("Local Cache diagnostic log")
            appendLine(header)
            appendLine("enabled=$enabled lines=${lines.size}")
            file?.let { appendLine("file=${it.absolutePath}") }
            appendLine("---")
            lines.forEach { appendLine(it) }
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        file?.let { dest ->
            runCatching { dest.writeText("") }
        }
    }

    private fun add(level: String, msg: String) {
        Log.i(TAG, msg)
        if (!enabled) return
        val line = "${ts()} $level $msg"
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        val dest = file ?: return
        runCatching {
            dest.appendText(line + "\n")
            if (dest.length() > MAX_FILE_BYTES) {
                val kept = dest.readLines().takeLast(500).joinToString("\n", postfix = "\n")
                dest.writeText(kept)
            }
        }
    }

    private fun ts(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date())
    }
}
