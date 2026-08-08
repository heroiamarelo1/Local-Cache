package app.localcache.server

import android.content.Context
import android.util.Log
import app.localcache.storage.CacheEntry
import app.localcache.storage.CacheRegistry
import app.localcache.storage.DownloadEngine
import app.localcache.storage.PlaybackStatus
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Serves the movie from the USB drive.
 *
 * Playing a stream starts the download, then everything the player reads comes off the
 * drive — the debrid service is only contacted again if the download itself failed.
 * Because the file is read while it is still growing, playback starts almost immediately
 * and the download stays ahead of watching.
 */
object VideoHandler {
    private const val TAG = "VideoHandler"

    /** Give up on a growing file if the download stops making progress for this long. */
    private const val STALL_TIMEOUT_MS = 90_000L

    private const val POLL_MS = 250L

    /** Size of each disk read when serving, to keep flash access sequential and chunky. */
    private const val READ_AHEAD_BYTES = 2 * 1024 * 1024

    /** No more bytes are coming for these, so waiting on the file is pointless. */
    private val TERMINAL_STATUSES = setOf("error", "paused", "cancelled")

    /** How often hybrid playback re-checks local vs debrid. */
    private const val HANDOVER_CHECK_MS = 1_500L

    /**
     * Local must stay this far ahead of the playhead before we prefer the drive.
     * Too small and we hand over then immediately stall when download dips.
     */
    private const val HANDOVER_MARGIN_BYTES = 64L * 1024 * 1024

    /** If local is only this far ahead (or less), fall back to debrid. */
    private const val FALLBACK_MARGIN_BYTES = 8L * 1024 * 1024

    /** How far past the written point still counts as "the downloader is nearly there". */
    private const val GROWING_GAP_BYTES = 8L * 1024 * 1024

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    fun serve(context: Context, session: IHTTPSession, cacheKey: String, headOnly: Boolean): Response {
        val entry = CacheRegistry.get(cacheKey)
        if (entry == null || entry.url.isBlank()) {
            return json(
                Status.NOT_FOUND,
                """{"error":"Unknown stream","hint":"Open the stream list in ${app.localcache.AppVariant.clientName} first"}""",
            )
        }

        // The click that starts playback is what starts the download.
        CacheRegistry.markAccessed(cacheKey)
        DownloadEngine.ensureStarted(context, cacheKey)
        CacheRegistry.refreshFromDisk(cacheKey)

        val totalBytes = ensureTotalBytes(entry)
        val type = contentType(entry.filePath ?: ".mp4")

        if (headOnly) {
            return headResponse(type, totalBytes)
        }

        val rangeHeader = session.headers["range"] ?: session.headers["Range"]
        val parsed = parseRangeHeader(rangeHeader)
        val hasRange = rangeHeader != null

        if (entry.filePath == null) {
            Log.w(TAG, "no cache target for $cacheKey (${entry.lastError}) — streaming direct")
            PlaybackStatus.markStreaming(cacheKey, entry.lastError ?: "no cache folder")
            return proxyToClient(entry.url, parsed.start, requestEnd(parsed, totalBytes, 0), totalBytes, hasRange, type)
        }

        val available = availableNow(entry)
        val onDisk = available?.size ?: 0L
        val haveStart = available != null && (available.complete || available.size > parsed.start)
        val complete = entry.status == "complete" ||
            (entry.filePath != null && File(entry.filePath!!).exists() && available?.complete == true)

        val end = requestEnd(parsed, totalBytes, onDisk)
        if (end < parsed.start) {
            return json(Status.RANGE_NOT_SATISFIABLE, """{"error":"Bad range"}""")
        }

        // Incomplete HTTP downloads: hybrid (local when safely ahead, else debrid).
        // One-way handover used to stall when the .part file lost the race with playback.
        if (!complete && isHttpUrl(entry.url)) {
            val preferLocal = haveStart && onDisk > parsed.start + HANDOVER_MARGIN_BYTES
            if (preferLocal) {
                Log.i(TAG, "hybrid $cacheKey start local: ${parsed.start}-$end (on disk $onDisk)")
                PlaybackStatus.markLocal(cacheKey, "serving bytes $onDisk on disk")
            } else {
                val reason = entry.lastError ?: "download is at $onDisk"
                Log.i(TAG, "hybrid $cacheKey start debrid: ${parsed.start}- ($reason)")
                PlaybackStatus.markStreaming(cacheKey, reason)
            }
            return serveHybrid(entry, parsed.start, end, totalBytes, hasRange, type, startOnDisk = preferLocal)
        }

        // Complete (or nearly local) files: progressive from storage.
        if (haveStart || arrivingShortly(entry, parsed.start, onDisk, totalBytes)) {
            Log.i(TAG, "serving $cacheKey from storage: ${parsed.start}-$end (on disk $onDisk)")
            PlaybackStatus.markLocal(cacheKey, "serving bytes $onDisk on disk")
            return serveProgressive(entry, parsed.start, end, totalBytes, hasRange, type)
        }

        val reason = entry.lastError ?: "download is at $onDisk"
        Log.i(TAG, "range ${parsed.start}- of $cacheKey not on disk ($reason) — starting on debrid")
        PlaybackStatus.markStreaming(cacheKey, reason)
        return serveHybrid(entry, parsed.start, end, totalBytes, hasRange, type, startOnDisk = false)
    }

    /** Inclusive end byte we promise to deliver for this request. */
    private fun requestEnd(parsed: ParsedRange, totalBytes: Long, onDisk: Long): Long {
        parsed.end?.let { if (it >= 0) return it }
        if (totalBytes > 0) return totalBytes - 1
        if (onDisk > 0) return onDisk - 1
        return parsed.start
    }

    private data class Available(val file: File, val size: Long, val complete: Boolean)

    /** The finished file if it exists, otherwise the `.part` being written. */
    private fun availableNow(entry: CacheEntry): Available? {
        val path = entry.filePath ?: return null
        val final = File(path)
        if (final.exists()) return Available(final, final.length(), true)
        val part = File("$path.part")
        if (part.exists()) return Available(part, part.length(), false)
        return null
    }

    /**
     * True when the downloader is about to write this offset, so it is worth serving from
     * the drive and letting the body stream wait, rather than opening a second connection
     * to debrid that would compete with the download for the same bandwidth.
     */
    private fun arrivingShortly(
        entry: CacheEntry,
        start: Long,
        onDisk: Long,
        totalBytes: Long,
    ): Boolean {
        if (totalBytes <= 0) return false
        if (entry.status in TERMINAL_STATUSES) return false
        return start <= onDisk + GROWING_GAP_BYTES
    }

    private fun serveHybrid(
        entry: CacheEntry,
        start: Long,
        end: Long,
        totalBytes: Long,
        hasRange: Boolean,
        type: String,
        startOnDisk: Boolean,
    ): Response {
        val length = end - start + 1
        val status = if (hasRange) Status.PARTIAL_CONTENT else Status.OK
        val body = HybridInputStream(entry, start, length, startOnDisk)
        val response = NanoHTTPD.newFixedLengthResponse(status, type, body, length)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        if (hasRange) {
            val total = if (totalBytes > 0) totalBytes.toString() else "*"
            response.addHeader("Content-Range", "bytes $start-$end/$total")
        }
        return response
    }

    private fun serveProgressive(
        entry: CacheEntry,
        start: Long,
        end: Long,
        totalBytes: Long,
        hasRange: Boolean,
        type: String,
    ): Response {
        val length = end - start + 1
        val status = if (hasRange) Status.PARTIAL_CONTENT else Status.OK

        val response = NanoHTTPD.newFixedLengthResponse(
            status,
            type,
            GrowingFileInputStream(entry, start, length),
            length,
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        if (hasRange) {
            val total = if (totalBytes > 0) totalBytes.toString() else "*"
            response.addHeader("Content-Range", "bytes $start-$end/$total")
        }
        return response
    }

    /**
     * Reads the cached file while the download is still writing it. When it reaches the end
     * of what has been written it waits for more, and follows the `.part` -> final rename.
     */
    private class GrowingFileInputStream(
        private val entry: CacheEntry,
        start: Long,
        length: Long,
    ) : InputStream() {
        private var position = start
        private var remaining = length
        private var handle: RandomAccessFile? = null
        private var openPath: String? = null
        private var closed = false

        // The HTTP layer pulls 16 KB at a time. Reading the drive in 16 KB pieces while the
        // downloader writes to the same file makes a flash stick crawl, so each disk touch
        // grabs a big block and the small reads are served from memory.
        private val chunk = ByteArray(READ_AHEAD_BYTES)
        private var chunkStart = -1L
        private var chunkLength = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (closed || remaining <= 0) return -1

            if (!positionBuffered() && !fillChunk()) return -1

            val within = (position - chunkStart).toInt()
            val available = chunkLength - within
            val toCopy = minOf(count, available, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            System.arraycopy(chunk, within, buffer, offset, toCopy)
            position += toCopy
            remaining -= toCopy
            return toCopy
        }

        private fun positionBuffered(): Boolean =
            chunkStart >= 0 && position >= chunkStart && position < chunkStart + chunkLength

        /** Waits for data if the download has not reached this point yet. */
        private fun fillChunk(): Boolean {
            var idleSince = System.currentTimeMillis()
            var lastSize = -1L

            while (!closed) {
                val file = currentFile()
                val size = file?.length() ?: 0L

                if (file != null && position < size) {
                    val raf = open(file) ?: return false
                    val want = minOf(
                        READ_AHEAD_BYTES.toLong(),
                        remaining,
                        size - position,
                    ).toInt()
                    raf.seek(position)
                    val read = raf.read(chunk, 0, want)
                    if (read > 0) {
                        chunkStart = position
                        chunkLength = read
                        return true
                    }
                }

                if (size != lastSize) {
                    lastSize = size
                    idleSince = System.currentTimeMillis()
                }

                if (entry.status == "complete" && position >= size) return false
                if (entry.status in TERMINAL_STATUSES) {
                    Log.i(TAG, "stop serving ${entry.cacheKey}: ${entry.status}")
                    return false
                }
                if (System.currentTimeMillis() - idleSince > STALL_TIMEOUT_MS) {
                    Log.w(TAG, "stalled at $position of ${entry.cacheKey}")
                    return false
                }

                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            return false
        }

        private fun currentFile(): File? {
            val path = entry.filePath ?: return null
            val final = File(path)
            if (final.exists()) return final
            val part = File("$path.part")
            if (part.exists()) return part
            return null
        }

        private fun open(file: File): RandomAccessFile? {
            if (openPath != file.absolutePath) {
                runCatching { handle?.close() }
                handle = runCatching { RandomAccessFile(file, "r") }.getOrNull()
                openPath = if (handle != null) file.absolutePath else null
            }
            return handle
        }

        override fun available(): Int {
            val size = currentFile()?.length() ?: return 0
            return minOf(remaining, maxOf(0, size - position)).toInt()
        }

        override fun close() {
            closed = true
            runCatching { handle?.close() }
            handle = null
        }
    }

    /**
     * Two-way hybrid: prefer the growing local file when it is safely ahead of the playhead,
     * otherwise read from the debrid URL. If local falls behind again after a handover,
     * reopen debrid from the current byte offset (one-way handover used to stall here).
     */
    private class HybridInputStream(
        private val entry: CacheEntry,
        startOffset: Long,
        length: Long,
        startOnDisk: Boolean,
    ) : InputStream() {
        private var position = startOffset
        private var remaining = length
        private var onDisk = startOnDisk
        private var local: RandomAccessFile? = null
        private var localPath: String? = null
        private var upstream: InputStream? = null
        private var nextCheckAt = 0L
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (closed || remaining <= 0) return -1
            maybeSwitch()

            val want = minOf(count.toLong(), remaining).toInt()
            if (want <= 0) return -1

            if (onDisk) {
                val n = readLocal(buffer, offset, want)
                if (n > 0) {
                    position += n
                    remaining -= n
                    return n
                }
                // Local miss — fall back to debrid for this range.
                Log.i(TAG, "hybrid fallback to debrid at $position for ${entry.cacheKey}")
                PlaybackStatus.markStreaming(entry.cacheKey, "local fell behind at $position")
                onDisk = false
                closeLocal()
                openUpstream()
            }

            val source = upstream ?: run {
                openUpstream()
                upstream
            } ?: return -1
            val n = source.read(buffer, offset, want)
            if (n <= 0) return -1
            position += n
            remaining -= n
            return n
        }

        private fun maybeSwitch() {
            val now = System.currentTimeMillis()
            if (now < nextCheckAt) return
            nextCheckAt = now + HANDOVER_CHECK_MS
            val ahead = localAhead()
            if (onDisk) {
                if (ahead < FALLBACK_MARGIN_BYTES && entry.status != "complete") {
                    Log.i(TAG, "hybrid cushion low at $position for ${entry.cacheKey} (ahead=$ahead)")
                    PlaybackStatus.markStreaming(entry.cacheKey, "local cushion low at $position")
                    onDisk = false
                    closeLocal()
                    openUpstream()
                }
            } else if (ahead >= HANDOVER_MARGIN_BYTES || entry.status == "complete") {
                Log.i(TAG, "hybrid handover to USB at $position for ${entry.cacheKey} (ahead=$ahead)")
                PlaybackStatus.markLocal(entry.cacheKey, "serving bytes ${position + ahead} on disk")
                closeUpstream()
                onDisk = true
            }
        }

        private fun localAhead(): Long {
            val file = currentLocalFile() ?: return -1L
            return file.length() - position
        }

        private fun currentLocalFile(): File? {
            val path = entry.filePath ?: return null
            val final = File(path)
            if (final.exists()) return final
            val part = File("$path.part")
            return if (part.exists()) part else null
        }

        private fun readLocal(buffer: ByteArray, offset: Int, count: Int): Int {
            val file = currentLocalFile() ?: return -1
            if (file.length() <= position) return -1
            val raf = openLocal(file) ?: return -1
            val want = minOf(count.toLong(), file.length() - position).toInt()
            if (want <= 0) return -1
            return try {
                raf.seek(position)
                raf.read(buffer, offset, want)
            } catch (e: Exception) {
                Log.w(TAG, "hybrid local read failed: ${e.message}")
                -1
            }
        }

        private fun openLocal(file: File): RandomAccessFile? {
            if (localPath != file.absolutePath) {
                closeLocal()
                local = runCatching { RandomAccessFile(file, "r") }.getOrNull()
                localPath = if (local != null) file.absolutePath else null
            }
            return local
        }

        private fun openUpstream() {
            if (upstream != null) return
            if (!isHttpUrl(entry.url)) return
            try {
                val end = if (remaining > 0) position + remaining - 1 else -1L
                val proxy = UpstreamProxy.openRangeSync(entry.url, position, end, entry.totalBytes)
                upstream = proxy.body.byteStream()
            } catch (e: Exception) {
                Log.e(TAG, "hybrid upstream open failed: ${e.message}")
                upstream = null
            }
        }

        private fun closeLocal() {
            runCatching { local?.close() }
            local = null
            localPath = null
        }

        private fun closeUpstream() {
            runCatching { upstream?.close() }
            upstream = null
        }

        override fun close() {
            closed = true
            closeLocal()
            closeUpstream()
        }
    }

    private fun headResponse(type: String, totalBytes: Long): Response {
        val response = NanoHTTPD.newFixedLengthResponse(Status.OK, type, "")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        if (totalBytes > 0) {
            response.addHeader("Content-Length", totalBytes.toString())
        }
        return response
    }

    private fun ensureTotalBytes(entry: CacheEntry): Long {
        if (entry.totalBytes > 0) return entry.totalBytes
        val total = UpstreamProxy.fetchTotalBytesSync(entry.url)
        if (total > 0) entry.totalBytes = total
        return entry.totalBytes
    }

    /** Direct proxy when there is no cache folder at all. */
    private fun proxyToClient(
        url: String,
        start: Long,
        end: Long,
        totalBytes: Long,
        hasRange: Boolean,
        type: String,
    ): Response {
        val proxy = try {
            UpstreamProxy.openRangeSync(url, start, end, totalBytes)
        } catch (e: Exception) {
            Log.e(TAG, "proxy failed: ${e.message}")
            return json(
                Status.INTERNAL_ERROR,
                """{"error":"Upstream proxy failed","message":"${e.message}"}""",
            )
        }

        val chunkSize = proxy.responseEnd - proxy.responseStart + 1
        val status = if (hasRange) Status.PARTIAL_CONTENT else Status.OK
        val response = NanoHTTPD.newFixedLengthResponse(
            status,
            type,
            proxy.body.byteStream(),
            chunkSize,
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        if (hasRange) {
            response.addHeader(
                "Content-Range",
                "bytes ${proxy.responseStart}-${proxy.responseEnd}/${proxy.total}",
            )
        } else if (proxy.total > 0) {
            response.addHeader("Content-Length", proxy.total.toString())
        }
        return response
    }

    private fun json(status: Status, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private data class ParsedRange(val start: Long, val end: Long?)

    private fun parseRangeHeader(rangeHeader: String?): ParsedRange {
        if (rangeHeader == null) return ParsedRange(0, null)
        val match = Regex("""bytes=(\d+)-(\d*)""").find(rangeHeader) ?: return ParsedRange(0, null)
        val start = match.groupValues[1].toLongOrNull() ?: 0L
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
        return ParsedRange(start, end)
    }

    private fun contentType(name: String): String = when {
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".avi", true) -> "video/x-msvideo"
        else -> "video/mp4"
    }
}
