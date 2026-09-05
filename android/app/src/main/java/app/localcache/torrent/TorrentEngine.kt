package app.localcache.torrent

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
                    // mmap I/O breaks on exFAT USB sticks; posix writes are slower and reliable.
                    val params = SessionParams(defaultSettings())
                    params.setPosixDiskIO()
                    manager.start(params)
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
        // Random port, IPv4 only. Pinning 6881 plus IPv6 made the whole listen
        // setup fail on boxes where v6 is disabled or 6881 is already taken.
        listenInterfaces("0.0.0.0:0")
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

            // Proven locally: DEFAULT_DONT_DOWNLOAD combined with renameFile leaves the
            // torrent wanting data but never connecting. Sequential + an immediate resume
            // is the path that actually fetched pieces. Extra files are ignored after
            // metadata, once we know which one we want.
            manager.download(magnet, saveDir, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val added = awaitHandle(manager, sha1)
                ?: return Result(false, "Torrent could not be added to the session")
            handle = added

            // parse_magnet_uri starts torrents paused+auto_managed. If we drop auto-manage
            // and do not resume, metadata never arrives and progress sits at 0% forever.
            runCatching { added.unsetFlags(TorrentFlags.AUTO_MANAGED) }
            runCatching { added.unsetFlags(TorrentFlags.PAUSED.or_(TorrentFlags.UPLOAD_MODE)) }
            runCatching { added.resume() }

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

            // Pause so we can pick the one file and rename it before more of the torrent
            // lands under the release's original folder name.
            runCatching { added.pause() }

            val priorities = Priority.array(Priority.IGNORE, torrentInfo.numFiles())
            priorities[fileIndex] = Priority.TOP_PRIORITY
            runCatching { added.prioritizeFiles(priorities) }
                .onFailure { return Result(false, "Could not select the file: ${it.message}") }
            if (added.filePriority(fileIndex) == Priority.IGNORE) {
                runCatching { added.filePriority(fileIndex, Priority.TOP_PRIORITY) }
            }

            // torrentFile().filePath() does not update after renameFile. The write still
            // goes to the new name — waiting for the path to change aborted working downloads.
            runCatching { added.renameFile(fileIndex, partFile.name) }
                .onFailure { Log.w(TAG, "renameFile failed: ${it.message}") }

            if (resuming) {
                Log.i(TAG, "rechecking existing ${partFile.name} (${partFile.length()} bytes)")
                report(entry, "checking the part already on disk…")
                runCatching { added.forceRecheck() }
            }

            runCatching { added.unsetFlags(TorrentFlags.PAUSED.or_(TorrentFlags.UPLOAD_MODE)) }
            runCatching { added.resume() }

            val pieceLength = torrentInfo.pieceLength().toLong()
            if (pieceLength <= 0) return Result(false, "Torrent piece size was 0")
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

            val written = findWrittenFile(saveDir, partFile, torrentInfo, fileIndex)
            if (written == null) {
                return Result(false, "Torrent finished but the video file was not on disk")
            }
            if (!renameWhenReleased(written, finalFile)) {
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
     * libtorrent may write to our `.part` name, or to the torrent's original file name if
     * the rename did not stick. Playback and the quota scanner only know about [partFile],
     * so we have to find whichever path actually received the bytes.
     */
    private fun findWrittenFile(
        saveDir: File,
        partFile: File,
        info: TorrentInfo,
        fileIndex: Int,
    ): File? {
        val original = info.files().filePath(fileIndex)
        val candidates = listOf(
            partFile,
            File(saveDir, partFile.name),
            File(saveDir, File(original).name),
            File(saveDir, original),
        ).distinct()
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
    }

    /** The session releases the file asynchronously, so the rename gets a few attempts. */
    private fun renameWhenReleased(from: File, finalFile: File): Boolean {
        if (from.absolutePath == finalFile.absolutePath) return from.exists()
        if (!from.exists()) return finalFile.exists()
        repeat(20) {
            if (from.renameTo(finalFile)) return true
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }
}
