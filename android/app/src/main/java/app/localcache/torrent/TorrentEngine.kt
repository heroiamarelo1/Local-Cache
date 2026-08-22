package app.localcache.torrent

import android.content.Context
import android.util.Log
import app.localcache.storage.CacheEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File

/**
 * BitTorrent side of the cache, used when a stream is a magnet instead of a debrid HTTP link.
 *
 * Everything is shaped to look exactly like the HTTP downloader to the rest of the app: one
 * torrent at a time, one video file, written to the same `<cacheKey>.<ext>.part` path, renamed
 * to the final name when it finishes. Playback then reads the growing file the same way.
 *
 * The one thing callers must respect is that a partially downloaded torrent file is **sparse**:
 * `File.length()` reports the full size from the first byte written. [CacheEntry.downloadedBytes]
 * is the contiguous, actually-readable prefix and is the only safe measure for playback.
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    /** Magnet metadata is usually seconds; a dead torrent should not hang the queue for long. */
    private const val METADATA_TIMEOUT_MS = 90_000L

    /** Give a torrent this long to produce its first byte before calling it dead. */
    private const val FIRST_BYTES_TIMEOUT_MS = 5 * 60_000L

    /** Stop waiting when a live torrent stops making progress entirely. */
    private const val STALL_TIMEOUT_MS = 10 * 60_000L

    private const val POLL_MS = 1_000L

    /** Upload cap so seeding never competes with the movie the user is watching. */
    private const val UPLOAD_LIMIT_BYTES_PER_SEC = 1024 * 1024

    /** Pieces to rush when playback seeks past what has been downloaded. */
    private const val SEEK_PIECE_WINDOW = 24

    /**
     * How far ahead of the playhead to look for readable pieces. Playback only ever reads a
     * couple of MB at a time, so there is no point walking a whole finished torrent — and each
     * check is a JNI call.
     */
    private const val LOOKAHEAD_PIECES = 256

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "webm", "mpg", "mpeg")

    private val lock = Any()

    @Volatile
    private var session: SessionManager? = null

    @Volatile
    private var loadError: String? = null

    /** The torrent currently being written, so playback can ask for pieces it needs sooner. */
    @Volatile
    private var active: Active? = null

    /** What the engine is doing right now, for the notification and /settings. */
    @Volatile
    private var phase: String? = null

    private class Active(
        val cacheKey: String,
        val handle: TorrentHandle,
        val fileOffset: Long,
        val fileSize: Long,
        val pieceLength: Long,
        val lastPiece: Int,
    )

    data class Result(val ok: Boolean, val error: String? = null)

    /** True once the native library has loaded. Reports why it did not when it fails. */
    fun nativeError(): String? = loadError

    private fun ensureSession(): SessionManager? {
        session?.let { if (it.isRunning) return it }
        synchronized(lock) {
            session?.let { if (it.isRunning) return it }
            return try {
                val manager = session ?: SessionManager().also { session = it }
                if (!manager.isRunning) {
                    manager.start(SessionParams(defaultSettings()))
                    manager.startDht()
                    Log.i(TAG, "libtorrent session started")
                }
                loadError = null
                manager
            } catch (e: Throwable) {
                // UnsatisfiedLinkError on an ABI we did not ship lands here.
                loadError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "could not start libtorrent session: $loadError", e)
                session = null
                null
            }
        }
    }

    private fun defaultSettings(): SettingsPack = SettingsPack().apply {
        connectionsLimit(200)
        activeDownloads(4)
        activeSeeds(2)
        activeLimit(8)
        uploadRateLimit(UPLOAD_LIMIT_BYTES_PER_SEC)
        downloadRateLimit(0)
        listenInterfaces("0.0.0.0:6881,[::]:6881")
        setEnableDht(true)
        setEnableLsd(true)
        setDhtBootstrapNodes(
            "router.bittorrent.com:6881,dht.transmissionbt.com:6881," +
                "router.utorrent.com:6881,dht.libtorrent.org:25401",
        )
    }

    fun shutdown() {
        synchronized(lock) {
            active = null
            runCatching { session?.stop() }
            session = null
        }
    }

    /**
     * Drops [cacheKey] out of the session right now. The download loop only checks its cancel
     * flag once a second, which is too slow when the caller is about to delete the file.
     */
    fun abort(cacheKey: String) {
        val current = active ?: return
        if (current.cacheKey != cacheKey) return
        active = null
        runCatching { current.handle.pause() }
        runCatching { session?.remove(current.handle) }
        Log.i(TAG, "aborted $cacheKey")
    }

    /**
     * Live line for the notification and /settings. Includes libtorrent's own state name: a
     * torrent that reads "downloading, 40 peers" but never moves is a very different problem
     * from one stuck in "downloading_metadata" with none.
     */
    fun statusDetail(): String? {
        val current = active ?: return phase
        val status = runCatching { current.handle.status() }.getOrNull() ?: return phase
        val state = status.state().name.lowercase().replace('_', ' ')
        return "$state · ${status.numPeers()} peers · ${status.numSeeds()} seeds"
    }

    /**
     * Downloads one file out of [entry]'s magnet into [partFile], then renames it to [finalFile].
     *
     * Blocks until the file is complete, cancelled, or judged dead. [onMetadata] is called once
     * the real file size is known and must return false to abort (for example: no room on disk).
     */
    fun download(
        entry: CacheEntry,
        partFile: File,
        finalFile: File,
        cancelled: () -> Boolean,
        onMetadata: (totalBytes: Long) -> Boolean,
    ): Result {
        val manager = ensureSession()
            ?: return Result(false, "Torrent engine unavailable: ${loadError ?: "native library not loaded"}")

        val saveDir = partFile.parentFile
            ?: return Result(false, "No cache folder for torrent download")
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            return Result(false, "Cannot create cache folder ${saveDir.absolutePath}")
        }

        val magnet = MagnetLinks.withFallbackTrackers(entry.url)
        val infoHash = MagnetLinks.infoHashHex(entry.url)
            ?: return Result(false, "Magnet has no usable info hash")
        val sha1 = runCatching { Sha1Hash.parseHex(infoHash) }.getOrNull()
            ?: return Result(false, "Magnet info hash could not be parsed")

        var handle: TorrentHandle? = null

        try {
            // A torrent is added exactly once. Adding it twice is not an error libtorrent
            // reports — the second add silently returns the first handle, and if that one was
            // still winding down from a previous attempt it sits at 0% forever.
            dropFromSession(manager, sha1)
            if (cancelled()) return Result(false, null)

            report(entry, "finding peers for the torrent…")
            Log.i(TAG, "adding $infoHash for ${entry.cacheKey} -> ${saveDir.absolutePath}")

            // DEFAULT_DONT_DOWNLOAD: a magnet's file list is unknown until metadata lands, and
            // without this libtorrent would immediately start allocating every file in the
            // torrent across the cache folder before we can say we only want one of them.
            manager.download(
                magnet,
                saveDir,
                TorrentFlags.SEQUENTIAL_DOWNLOAD.or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD),
            )

            val added = awaitHandle(manager, sha1)
                ?: return Result(false, "Torrent could not be added to the session")
            handle = added

            // Auto-manage would pause and resume the torrent behind our back.
            runCatching { added.unsetFlags(TorrentFlags.AUTO_MANAGED) }

            val torrentInfo = awaitMetadata(added, entry, cancelled)
            if (cancelled()) return Result(false, null)
            if (torrentInfo == null) {
                val peers = runCatching { added.status().numPeers() }.getOrDefault(0)
                return Result(false, "No peers sent the torrent details ($peers peers reached)")
            }

            val files = torrentInfo.files()
            val fileIndex = pickVideoFile(torrentInfo, entry.fileIndex)
            if (fileIndex < 0) return Result(false, "Torrent has no video file")

            val fileSize = files.fileSize(fileIndex)
            entry.totalBytes = fileSize
            Log.i(
                TAG,
                "metadata ok for ${entry.cacheKey}: ${files.fileName(fileIndex)} ($fileSize bytes, " +
                    "file $fileIndex of ${torrentInfo.numFiles()})",
            )

            if (!onMetadata(fileSize)) return Result(false, entry.lastError)
            if (cancelled()) return Result(false, null)

            val resuming = partFile.exists() && partFile.length() > 0

            // Rename before any file is wanted, so not one byte can land on the wrong path.
            if (!placeFileAt(added, fileIndex, partFile)) {
                return Result(false, "Could not place the torrent file in the cache folder")
            }

            val priorities = Priority.array(Priority.IGNORE, torrentInfo.numFiles())
            priorities[fileIndex] = Priority.TOP_PRIORITY
            runCatching { added.prioritizeFiles(priorities) }
                .onFailure { return Result(false, "Could not select the file: ${it.message}") }

            if (resuming) {
                Log.i(TAG, "rechecking existing ${partFile.name} (${partFile.length()} bytes)")
                report(entry, "checking the part already on disk…")
                runCatching { added.forceRecheck() }
            }

            // Belt and braces: nothing above should have left it paused or upload-only, but a
            // torrent stuck in either state is invisible except as a download that never moves.
            runCatching { added.unsetFlags(TorrentFlags.PAUSED.or_(TorrentFlags.UPLOAD_MODE)) }
            runCatching { added.resume() }

            val pieceLength = torrentInfo.pieceLength().toLong()
            val fileOffset = files.fileOffset(fileIndex)
            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()

            active = Active(
                cacheKey = entry.cacheKey,
                handle = added,
                fileOffset = fileOffset,
                fileSize = fileSize,
                pieceLength = pieceLength,
                lastPiece = lastPiece,
            )

            val result = pump(entry, added, firstPiece, lastPiece, fileOffset, fileSize, pieceLength, cancelled)
            if (!result.ok) return result

            // libtorrent still owns the file handle until the torrent leaves the session.
            active = null
            runCatching { manager.remove(added) }
            handle = null

            if (!renameWhenReleased(partFile, finalFile)) {
                return Result(false, "Could not rename the finished torrent file")
            }

            entry.status = "complete"
            entry.downloadedBytes = finalFile.length()
            entry.totalBytes = finalFile.length()
            entry.lastError = null
            Log.i(TAG, "complete ${entry.cacheKey} (${finalFile.length()} bytes)")
            return Result(true)
        } catch (e: Throwable) {
            Log.e(TAG, "torrent failed for ${entry.cacheKey}: ${e.message}", e)
            return Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            active = null
            phase = null
            entry.bytesPerSec = 0
            handle?.let { open ->
                // Cancelled or failed: drop it from the session but keep the .part for resume.
                runCatching { open.pause() }
                runCatching { manager.remove(open) }
            }
        }
    }

    /**
     * Removes any torrent already in the session for [sha1] and waits for it to actually go.
     * `remove_torrent` is asynchronous, and re-adding before it lands hands back the old
     * handle instead of a fresh one.
     */
    private fun dropFromSession(manager: SessionManager, sha1: Sha1Hash) {
        val existing = runCatching { manager.find(sha1) }.getOrNull() ?: return
        Log.i(TAG, "removing leftover torrent ${sha1.toHex()} before re-adding")
        runCatching { manager.remove(existing) }

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { manager.find(sha1) }.getOrNull() == null) return
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return
            }
        }
        Log.w(TAG, "leftover torrent ${sha1.toHex()} did not leave the session in time")
    }

    /** Waits for the swarm to send the file list a magnet does not carry. */
    private fun awaitMetadata(
        handle: TorrentHandle,
        entry: CacheEntry,
        cancelled: () -> Boolean,
    ): TorrentInfo? {
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (cancelled() || !handle.isValid) return null

            val status = runCatching { handle.status() }.getOrNull()
            if (status?.hasMetadata() == true) {
                val info = runCatching { handle.torrentFile() }.getOrNull()
                if (info != null && info.isValid) return info
            }

            val peers = status?.numPeers() ?: 0
            report(entry, if (peers > 0) "asking $peers peers for the file list…" else "looking for peers…")

            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun report(entry: CacheEntry, message: String?) {
        phase = message
        entry.lastError = message
    }

    /** Polls the torrent, publishing the contiguous readable prefix as it grows. */
    private fun pump(
        entry: CacheEntry,
        handle: TorrentHandle,
        firstPiece: Int,
        lastPiece: Int,
        fileOffset: Long,
        fileSize: Long,
        pieceLength: Long,
        cancelled: () -> Boolean,
    ): Result {
        var cursor = firstPiece
        var lastProgressAt = System.currentTimeMillis()
        var lastContiguous = -1L
        val startedAt = System.currentTimeMillis()

        while (true) {
            if (cancelled()) return Result(false, null)
            if (!handle.isValid) return Result(false, "Torrent was removed from the session")

            val status = runCatching { handle.status() }.getOrNull()

            while (cursor <= lastPiece && runCatching { handle.havePiece(cursor) }.getOrDefault(false)) {
                cursor++
            }
            val readableEnd = minOf(cursor.toLong() * pieceLength, fileOffset + fileSize)
            val contiguous = (readableEnd - fileOffset).coerceIn(0, fileSize)

            entry.downloadedBytes = contiguous
            entry.bytesPerSec = status?.downloadRate()?.toLong() ?: 0L

            if (contiguous > lastContiguous) {
                lastContiguous = contiguous
                lastProgressAt = System.currentTimeMillis()
            }

            if (cursor > lastPiece) return Result(true)
            if (status?.isFinished == true && contiguous >= fileSize) return Result(true)

            val peers = status?.numPeers() ?: 0
            val seeds = status?.numSeeds() ?: 0
            val state = status?.state()?.name?.lowercase().orEmpty()
            report(
                entry,
                when {
                    state.contains("checking") -> "checking the part already on disk…"
                    peers == 0 -> "searching for peers…"
                    // Verified MB separates "the swarm is not sending" from "it is sending but
                    // the piece bookkeeping here is wrong" — they look identical at 0%.
                    contiguous <= 0 ->
                        "$peers peers ($state) · ${(status?.totalWantedDone() ?: 0) / (1024 * 1024)} MB " +
                            "verified · waiting for the opening pieces"
                    else -> null
                },
            )

            val idleMs = System.currentTimeMillis() - lastProgressAt
            if (contiguous <= 0 && System.currentTimeMillis() - startedAt > FIRST_BYTES_TIMEOUT_MS) {
                val done = status?.totalWantedDone() ?: 0
                return Result(
                    false,
                    "Torrent sent no usable data in 5 min " +
                        "($state, $peers peers, $seeds seeds, ${done / (1024 * 1024)} MB verified)",
                )
            }
            if (contiguous > 0 && idleMs > STALL_TIMEOUT_MS) {
                return Result(false, "Torrent stalled at ${contiguous / (1024 * 1024)} MB ($peers peers)")
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return Result(false, null)
            }
        }
    }

    /**
     * How far playback can read without stopping, starting at [offset]. Returns [offset] itself
     * when the piece under the playhead has not arrived, and -1 when this torrent is not the one
     * running (the caller should fall back to the contiguous count from the start of the file).
     *
     * Playback needs this rather than "bytes downloaded from byte 0": after a seek the engine
     * rushes the pieces under the new playhead, and those become readable long before the
     * sequential download has filled the gap behind them.
     */
    fun readableEndFrom(cacheKey: String, offset: Long): Long {
        val current = active ?: return -1
        if (current.cacheKey != cacheKey) return -1
        if (offset < 0 || offset >= current.fileSize) return -1

        val handle = current.handle
        val first = ((current.fileOffset + offset) / current.pieceLength).toInt()
        return runCatching {
            if (!handle.havePiece(first)) return offset
            var piece = first + 1
            val stopAt = minOf(current.lastPiece, first + LOOKAHEAD_PIECES)
            while (piece <= stopAt && handle.havePiece(piece)) piece++
            val end = piece.toLong() * current.pieceLength - current.fileOffset
            end.coerceIn(offset, current.fileSize)
        }.getOrDefault(-1)
    }

    /**
     * Asks libtorrent to rush the pieces around [offset]. Playback calls this when the viewer
     * seeks past the sequential write head, so a scrub does not wait for the whole gap.
     */
    fun requestOffset(cacheKey: String, offset: Long) {
        val current = active ?: return
        if (current.cacheKey != cacheKey) return
        if (offset < 0 || offset >= current.fileSize) return

        val piece = ((current.fileOffset + offset) / current.pieceLength).toInt()
        val handle = current.handle
        runCatching {
            for (i in 0 until SEEK_PIECE_WINDOW) {
                val target = piece + i
                if (target > current.lastPiece) break
                if (handle.havePiece(target)) continue
                handle.setPieceDeadline(target, i * 200)
            }
        }
    }

    /** The file the user actually wants: the addon's index when sane, else the biggest video. */
    private fun pickVideoFile(info: TorrentInfo, preferred: Int?): Int {
        val files = info.files()
        val count = info.numFiles()
        if (count <= 0) return -1

        if (preferred != null && preferred in 0 until count && files.fileSize(preferred) > 0) {
            return preferred
        }

        var bestVideo = -1
        var bestVideoSize = -1L
        var bestAny = -1
        var bestAnySize = -1L

        for (i in 0 until count) {
            val size = files.fileSize(i)
            if (size > bestAnySize) {
                bestAnySize = size
                bestAny = i
            }
            val ext = files.fileName(i).substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTENSIONS && size > bestVideoSize) {
                bestVideoSize = size
                bestVideo = i
            }
        }
        return if (bestVideo >= 0) bestVideo else bestAny
    }

    private fun awaitHandle(manager: SessionManager, infoHash: Sha1Hash): TorrentHandle? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val handle = runCatching { manager.find(infoHash) }.getOrNull()
            if (handle != null && handle.isValid) return handle
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    /**
     * Renames the wanted file inside the torrent so libtorrent writes straight to our `.part`
     * path. Without this the file lands under the torrent's own folder and playback, the quota
     * scanner and the resume list all look in the wrong place.
     */
    private fun placeFileAt(handle: TorrentHandle, fileIndex: Int, partFile: File): Boolean {
        val wanted = partFile.name
        if (pathInTorrent(handle, fileIndex) == wanted) return true

        runCatching { handle.renameFile(fileIndex, wanted) }
            .onFailure { Log.w(TAG, "renameFile failed: ${it.message}") }

        var seen: String? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            seen = pathInTorrent(handle, fileIndex)
            if (seen == wanted) return true
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        Log.e(TAG, "torrent file stayed at '$seen' instead of '$wanted'")
        return false
    }

    private fun pathInTorrent(handle: TorrentHandle, fileIndex: Int): String? =
        runCatching { handle.torrentFile()?.files()?.filePath(fileIndex) }.getOrNull()

    /** The session releases the file asynchronously, so the rename gets a few attempts. */
    private fun renameWhenReleased(partFile: File, finalFile: File): Boolean {
        if (!partFile.exists()) return finalFile.exists()
        repeat(20) {
            if (partFile.renameTo(finalFile)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }
}
