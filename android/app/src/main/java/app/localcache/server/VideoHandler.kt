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

    /** How often a proxied response looks to see whether the drive has caught up. */
    private const val HANDOVER_CHECK_MS = 2_000L

    /** Cushion the download must hold over the playback point before handing over. */
    private const val HANDOVER_MARGIN_BYTES = 32L * 1024 * 1024

    /** How far past the written point still counts as "the downloader is nearly there". */
    private const val GROWING_GAP_BYTES = 8L * 1024 * 1024

    fun serve(context: Context, session: IHTTPSession, cacheKey: String, headOnly: Boolean): Response {
        val entry = CacheRegistry.get(cacheKey)
        if (entry == null || entry.url.isBlank()) {
            return json(
                Status.NOT_FOUND,
                """{"error":"Unknown stream","hint":"Open the stream list in Stremio first"}""",
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

        // Never block before replying. Waiting here sends the player nothing at all — not
        // even headers — and Stremio reads a silent socket as a dead stream and skips to the
        // next result. Answer immediately; GrowingFileInputStream does any waiting, by which
        // point the player is already receiving a response.
        if (haveStart || arrivingShortly(entry, parsed.start, onDisk, totalBytes)) {
            val end = requestEnd(parsed, totalBytes, onDisk)
            if (end < parsed.start) {
                return json(Status.RANGE_NOT_SATISFIABLE, """{"error":"Bad range"}""")
            }
            Log.i(TAG, "serving $cacheKey from storage: ${parsed.start}-$end (on disk $onDisk)")
            PlaybackStatus.markLocal(cacheKey, "serving bytes $onDisk on disk")
            return serveProgressive(entry, parsed.start, end, totalBytes, hasRange, type)
        }

        // Genuinely ahead of the download: the player reads the container index from the end
        // of the file before it can start, and seeking jumps past what has been written.
        // Start on the debrid link and hand over to the drive once it catches up.
        val reason = entry.lastError ?: "download is at $onDisk"
        Log.i(TAG, "range ${parsed.start}- of $cacheKey not on disk ($reason) — starting on debrid")
        PlaybackStatus.markStreaming(cacheKey, reason)
        return proxyToClient(
            entry.url,
            parsed.start,
            requestEnd(parsed, totalBytes, 0),
            totalBytes,
            hasRange,
            type,
            handoverEntry = entry,
        )
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
     * Starts on the debrid link and moves to the USB file part-way through, in the middle of
     * one HTTP response. The player sees a single continuous stream and never reconnects, so
     * a session that had to start on a flaky debrid link still ends up reading off the drive
     * once the download overtakes the playback position.
     */
    private class HandoverInputStream(
        private val entry: CacheEntry,
        startOffset: Long,
        length: Long,
        private val upstream: InputStream,
    ) : InputStream() {
        private var position = startOffset
        private var remaining = length
        private var disk: GrowingFileInputStream? = null
        private var nextCheckAt = System.currentTimeMillis() + HANDOVER_CHECK_MS
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (closed || remaining <= 0) return -1

            disk?.let { return pull(it, buffer, offset, count) }

            if (System.currentTimeMillis() >= nextCheckAt) {
                nextCheckAt = System.currentTimeMillis() + HANDOVER_CHECK_MS
                if (driveIsAhead()) {
                    Log.i(TAG, "handover to USB at $position for ${entry.cacheKey}")
                    runCatching { upstream.close() }
                    val local = GrowingFileInputStream(entry, position, remaining)
                    disk = local
                    return pull(local, buffer, offset, count)
                }
            }

            return pull(upstream, buffer, offset, count)
        }

        private fun pull(source: InputStream, buffer: ByteArray, offset: Int, count: Int): Int {
            val want = minOf(count.toLong(), remaining).toInt()
            if (want <= 0) return -1
            val read = source.read(buffer, offset, want)
            if (read <= 0) return -1
            position += read
            remaining -= read
            return read
        }

        /**
         * Only switch once the drive is comfortably ahead — handing over to a file that is
         * barely at the playback point would just stall waiting for the downloader.
         */
        private fun driveIsAhead(): Boolean {
            val path = entry.filePath ?: return false
            if (File(path).exists()) return true
            val part = File("$path.part")
            return part.exists() && part.length() > position + HANDOVER_MARGIN_BYTES
        }

        override fun close() {
            closed = true
            runCatching { upstream.close() }
            runCatching { disk?.close() }
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

    /**
     * Used when the drive cannot cover the request yet. The response is not locked to the
     * debrid link for its whole life: [handoverEntry] lets it move onto the USB file
     * mid-stream, without the player noticing, as soon as the download gets ahead.
     */
    private fun proxyToClient(
        url: String,
        start: Long,
        end: Long,
        totalBytes: Long,
        hasRange: Boolean,
        type: String,
        handoverEntry: CacheEntry? = null,
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

        val body = if (handoverEntry != null) {
            HandoverInputStream(handoverEntry, proxy.responseStart, chunkSize, proxy.body.byteStream())
        } else {
            proxy.body.byteStream()
        }

        val response = NanoHTTPD.newFixedLengthResponse(status, type, body, chunkSize)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        if (hasRange) {
            response.addHeader("Content-Range", "bytes ${proxy.responseStart}-${proxy.responseEnd}/${proxy.total}")
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
