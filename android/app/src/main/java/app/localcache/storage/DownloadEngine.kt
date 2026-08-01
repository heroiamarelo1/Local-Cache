package app.localcache.storage

import android.content.Context
import android.util.Log
import app.localcache.Prefs
import app.localcache.server.UpstreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadEngine {
    private const val TAG = "DownloadEngine"

    /** Anything smaller than this is an error page, not a movie. */
    private const val MIN_VALID_BYTES = 1024 * 1024L

    private const val WRITE_BUFFER_BYTES = 4 * 1024 * 1024

    /** Get something on disk fast so playback can begin, before favouring big writes. */
    private const val FIRST_FLUSH_BYTES = 512 * 1024L

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.HOURS)
        .build()

    // One download at a time: debrid links throttle parallel connections, and the drive
    // cannot write two movies and feed the player at once. Whatever the viewer just
    // pressed play on wins, and the previous download steps aside.
    private val lock = Any()
    private var activeJob: Job? = null
    private var activeRun: Run? = null

    @Volatile
    private var activeKeyValue: String? = null

    @Volatile
    private var appContext: Context? = null

    private data class Target(val final: File, val part: File)

    /** One download attempt, so a cancelled run can tell itself apart from a failure. */
    private class Run(val cacheKey: String) {
        @Volatile
        var cancelled = false

        @Volatile
        var call: Call? = null

        fun stop() {
            cancelled = true
            runCatching { call?.cancel() }
        }
    }

    fun ensureStarted(context: Context, cacheKey: String) {
        appContext = context.applicationContext
        val entry = CacheRegistry.get(cacheKey) ?: return
        val target = resolveTarget(context, entry) ?: return

        if (target.final.exists()) {
            entry.status = "complete"
            entry.downloadedBytes = target.final.length()
            entry.totalBytes = target.final.length()
            entry.lastError = null
            return
        }

        if (target.part.exists()) {
            entry.downloadedBytes = target.part.length()
        }

        val ctx = context.applicationContext

        synchronized(lock) {
            if (activeKeyValue == cacheKey && activeJob?.isActive == true) return

            // Newest request has priority — park whatever was running.
            val previous = stopActiveLocked("paused — another movie was started", deletePartial = false)

            val run = Run(cacheKey)
            activeRun = run
            activeKeyValue = cacheKey
            entry.status = "downloading"
            entry.lastError = null
            entry.bytesPerSec = 0
            Log.i(TAG, "start $cacheKey -> ${target.final.absolutePath}")

            activeJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Let the old connection finish tearing down before touching the drive.
                    previous?.join()

                    if (run.cancelled) return@launch

                    if (entry.totalBytes <= 0) {
                        val total = UpstreamProxy.fetchTotalBytesSync(entry.url)
                        if (total > 0) entry.totalBytes = total
                    }

                    if (run.cancelled) return@launch

                    val budget = makeRoom(ctx, entry, target) ?: return@launch

                    // Record where this file came from before writing a byte, so the drive
                    // stays self-describing even if the app or the upstream disappears.
                    LocalLibrary.writeMeta(entry)

                    download(entry, target, budget, run)
                } finally {
                    synchronized(lock) {
                        if (activeRun === run) {
                            activeRun = null
                            activeJob = null
                            activeKeyValue = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops the running download. Returns its job so a replacement can wait for the
     * connection and file handle to be released first.
     */
    private fun stopActiveLocked(reason: String, deletePartial: Boolean): Job? {
        val run = activeRun ?: return null
        val job = activeJob

        run.stop()

        CacheRegistry.get(run.cacheKey)?.let { entry ->
            if (entry.status == "downloading" || entry.status == "queued") {
                entry.status = "paused"
                entry.lastError = reason
            }
            entry.bytesPerSec = 0

            if (deletePartial) {
                entry.filePath?.let { path ->
                    val part = CachePaths.partFile(File(path))
                    if (part.exists() && part.delete()) {
                        Log.i(TAG, "discarded partial ${part.name}")
                    }
                }
                entry.downloadedBytes = 0
                entry.status = "cancelled"
            }
        }

        activeRun = null
        activeJob = null
        activeKeyValue = null
        Log.i(TAG, "stopped ${run.cacheKey}: $reason")
        return job
    }

    /**
     * Cancel the current download, e.g. when a stream turns out to be broken and the
     * viewer wants to try a different one. Returns a message for the UI.
     */
    fun cancelActive(deletePartial: Boolean = true): String {
        synchronized(lock) {
            if (activeKeyValue == null) return "Nothing is downloading"
            stopActiveLocked("cancelled from the app", deletePartial)
            return if (deletePartial) {
                "Cancelled download and freed its partial file"
            } else {
                "Paused download — partial file kept"
            }
        }
    }

    fun isBusy(): Boolean = synchronized(lock) { activeKeyValue != null }

    fun activeKey(): String? = activeKeyValue

    /**
     * Frees space for this movie and returns the maximum bytes it may occupy,
     * or null if it cannot fit even after deleting old files.
     */
    private fun makeRoom(context: Context, entry: CacheEntry, target: Target): Long? {
        val onDisk = if (target.part.exists()) target.part.length() else 0L
        val needed = (entry.totalBytes - onDisk).coerceAtLeast(0)
        val result = DiskQuota.ensureSpace(context, needed, setOf(entry.cacheKey))

        if (needed > 0 && !result.enough) {
            val message = "Needs ${gb(needed)} GB but only ${gb(result.roomBytes)} GB " +
                "is free after cleanup (limit ${Prefs.cacheMaxGb(context)} GB)"
            Log.e(TAG, "$message — ${entry.cacheKey}")
            entry.status = "error"
            entry.lastError = message
            return null
        }

        return onDisk + result.roomBytes
    }

    /** Also fills in [CacheEntry.filePath] so progress can be read back from disk. */
    private fun resolveTarget(context: Context, entry: CacheEntry): Target? {
        val cacheRoot = Prefs.cacheDirPath(context)?.let { File(it) } ?: run {
            Log.e(TAG, "no cache dir - pick a USB drive in the app first")
            entry.lastError = "USB not selected in Local Cache app"
            return null
        }

        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
            Log.e(TAG, "cannot create $cacheRoot")
            entry.lastError = "Cannot create folder on USB: $cacheRoot"
            return null
        }

        val finalFile = File(cacheRoot, CachePaths.safeName(entry.cacheKey) + CachePaths.guessExt(entry.url))
        entry.filePath = finalFile.absolutePath
        return Target(finalFile, CachePaths.partFile(finalFile))
    }

    private fun download(entry: CacheEntry, target: Target, budgetBytes: Long, run: Run) {
        val partFile = target.part
        var downloaded = if (partFile.exists()) partFile.length() else 0L
        entry.downloadedBytes = downloaded

        try {
            val builder = Request.Builder()
                .url(entry.url)
                .header("User-Agent", "LocalCache-TV/0.1.8")
            if (downloaded > 0) {
                builder.header("Range", "bytes=$downloaded-")
                Log.i(TAG, "resume ${entry.cacheKey} from $downloaded")
            }

            val call = client.newCall(builder.build())
            run.call = call
            if (run.cancelled) return

            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("Upstream HTTP ${response.code}")
                }

                val contentType = response.header("Content-Type")?.lowercase() ?: ""
                if (contentType.contains("json") ||
                    contentType.contains("javascript") ||
                    contentType.contains("html")
                ) {
                    throw IllegalStateException("Upstream sent $contentType, not a video")
                }

                // Server ignored our Range header - start over rather than corrupt the file.
                if (downloaded > 0 && response.code == 200) {
                    Log.w(TAG, "range ignored, restarting ${entry.cacheKey}")
                    downloaded = 0
                    entry.downloadedBytes = 0
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val contentLength = body.contentLength()
                entry.totalBytes = when (response.code) {
                    206 -> {
                        val range = response.header("Content-Range")
                        Regex("/(\\d+)$").find(range ?: "")?.groupValues?.get(1)?.toLongOrNull()
                            ?: (downloaded + contentLength)
                    }
                    else -> contentLength.coerceAtLeast(entry.totalBytes)
                }

                val raw = FileOutputStream(partFile, downloaded > 0)
                // Large coalesced writes: the player is reading the same file off the same
                // flash drive, and lots of small writes make the head-thrashing much worse.
                java.io.BufferedOutputStream(raw, WRITE_BUFFER_BYTES).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(512 * 1024)
                        var sampleAt = System.currentTimeMillis()
                        var sampleBytes = downloaded
                        val startedAt = downloaded
                        var openedPlayback = false

                        while (true) {
                            if (run.cancelled) throw Cancelled()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            entry.downloadedBytes = downloaded

                            // The player cannot start until bytes are visible on disk, and a
                            // 4 MB buffer can hold them back for seconds on a slow link.
                            // Publish the first chunk straight away, then resume coalescing.
                            if (!openedPlayback && downloaded - startedAt >= FIRST_FLUSH_BYTES) {
                                out.flush()
                                openedPlayback = true
                            }

                            val now = System.currentTimeMillis()
                            if (now - sampleAt >= 2000) {
                                entry.bytesPerSec = (downloaded - sampleBytes) * 1000 / (now - sampleAt)
                                sampleAt = now
                                sampleBytes = downloaded
                            }

                            if (downloaded > budgetBytes) {
                                throw OutOfSpace(
                                    "Movie is bigger than the ${gb(budgetBytes)} GB of space available",
                                )
                            }
                        }
                    }
                    out.flush()
                    raw.fd.sync()
                }

                if (downloaded < MIN_VALID_BYTES) {
                    partFile.delete()
                    throw IllegalStateException("Only $downloaded bytes received")
                }

                if (!partFile.renameTo(target.final)) {
                    throw IllegalStateException("Could not rename .part on USB")
                }

                entry.status = "complete"
                entry.downloadedBytes = target.final.length()
                entry.totalBytes = target.final.length()
                entry.lastError = null
                Log.i(TAG, "complete ${entry.cacheKey} (${target.final.length()} bytes)")
            }
        } catch (e: Exception) {
            entry.bytesPerSec = 0

            // A deliberate stop is not a failure — leave the .part alone so the same
            // movie can pick up where it left off if the viewer comes back to it.
            if (run.cancelled || e is Cancelled) {
                Log.i(TAG, "cancelled ${entry.cacheKey} at ${entry.downloadedBytes} bytes")
                return
            }

            Log.e(TAG, "failed ${entry.cacheKey}: ${e.message}")
            entry.status = "error"
            entry.lastError = e.message ?: e.javaClass.simpleName
            // A file that cannot fit will never finish — reclaim what it took.
            if (e is OutOfSpace) {
                partFile.delete()
                entry.downloadedBytes = 0
            }
        }
    }

    private class OutOfSpace(message: String) : IllegalStateException(message)

    private class Cancelled : IllegalStateException("cancelled")

    /** Short line for the ongoing notification and the app screen. */
    fun statusLine(): String {
        val key = activeKeyValue ?: return "Idle - ready for Stremio"
        val entry = CacheRegistry.get(key) ?: return "Downloading…"
        val done = entry.downloadedBytes
        val total = entry.totalBytes
        val speed = if (entry.bytesPerSec > 0) " @ ${speedText(entry.bytesPerSec)}" else ""
        val name = entry.label
            .replace('\n', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { key }
        val shortName = if (name.length > 72) name.take(69) + "…" else name
        val progress = CacheRegistry.progress(key)
        val state = when (entry.status) {
            "paused" -> "paused — tap stream in Stremio to resume"
            "complete" -> "ready on USB"
            "downloading" -> "downloading"
            else -> entry.status
        }
        return buildString {
            appendLine(shortName)
            if (total > 0) {
                append("$progress% · ${gb(done)} / ${gb(total)} GB$speed · $state")
            } else {
                append("${gb(done)} GB$speed · $state")
            }
        }
    }

    /** MB/s plus Mbps, since stream bitrates are quoted in Mbps. */
    fun speedText(bytesPerSec: Long): String =
        "%.1f MB/s (%.0f Mbps)".format(bytesPerSec / 1_048_576.0, bytesPerSec * 8.0 / 1_000_000)

    fun activeSpeedBytesPerSec(): Long =
        activeKeyValue?.let { CacheRegistry.get(it)?.bytesPerSec } ?: 0

    private fun gb(bytes: Long): String = "%.2f".format(bytes / 1_073_741_824.0)
}
