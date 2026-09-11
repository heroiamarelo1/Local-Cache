package app.localcache.torrent

import android.util.Log
import app.localcache.DiagLog
import app.localcache.storage.CacheEntry
import org.libtorrent4j.AlertListener
import org.libtorrent4j.PieceIndexBitfield
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.status_flags_t
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

    /** Pieces to mark TOP when playback seeks past what has been downloaded. */
    private const val SEEK_PIECE_WINDOW = 8

    /** Opening window we keep at TOP_PRIORITY. One JNI array, not 24 deadline calls. */
    private const val HEAD_PIECES = 8

    /**
     * Players probe the last megabytes of an MKV for the cue index. Treating that as a
     * seek makes libtorrent fetch the end of the file and leave a hole at piece 1, which
     * looks like 0% forever.
     */
    private const val TAIL_PROBE_BYTES = 16L * 1024 * 1024

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

    @Volatile
    private var listenerAttached = false

    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null

        override fun alert(alert: Alert<*>) {
            if (!DiagLog.enabled) return
            val type = runCatching { alert.type() }.getOrNull() ?: return
            if (isNoisyAlert(type)) return
            val msg = runCatching { alert.message() }.getOrNull().orEmpty()
            DiagLog.t("alert $type $msg")
        }
    }

    private class Active(
        val cacheKey: String,
        val handle: TorrentHandle,
        val fileOffset: Long,
        val fileSize: Long,
        val pieceLength: Long,
        val firstPiece: Int,
        val lastPiece: Int,
        val numPieces: Int,
        @Volatile var cursor: Int,
        @Volatile var playhead: Long = 0L,
        @Volatile var haveBits: BooleanArray? = null,
        @Volatile var lastPrioCursor: Int = Int.MIN_VALUE,
        @Volatile var lastPrioSeek: Int = Int.MIN_VALUE,
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
                    if (!listenerAttached) {
                        manager.addListener(alertListener)
                        listenerAttached = true
                    }
                    Log.i(TAG, "libtorrent session started")
                    DiagLog.i("libtorrent session started (posix disk IO, listen 0.0.0.0:0)")
                }
                loadError = null
                manager
            } catch (e: Throwable) {
                // UnsatisfiedLinkError on an ABI we did not ship lands here.
                loadError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "could not start libtorrent session: $loadError", e)
                DiagLog.e("libtorrent session failed: $loadError")
                session = null
                null
            }
        }
    }

    private fun defaultSettings(): SettingsPack = SettingsPack().apply {
        connectionsLimit(80)
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
        // uTP connect timeouts ate the whole connection budget on the TV; TCP actually
        // reached seeders. Incoming uTP stays on so peers can still reach us.
        setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
        setBoolean(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true)
        maxQueuedDiskBytes(8 * 1024 * 1024)
    }

    fun shutdown() {
        synchronized(lock) {
            active = null
            val manager = session
            if (listenerAttached && manager != null) {
                runCatching { manager.removeListener(alertListener) }
                listenerAttached = false
            }
            runCatching { manager?.stop() }
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
            DiagLog.t(
                "add $infoHash key=${entry.cacheKey} save=${saveDir.absolutePath} " +
                    "part=${partFile.name} magnet=${magnet.take(220)}",
            )

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
            DiagLog.t("resumed after add ${snapshot(added)}")

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
            DiagLog.t(
                "metadata name=${files.fileName(fileIndex)} size=$fileSize " +
                    "index=$fileIndex/${torrentInfo.numFiles()} " +
                    "pieceLen=${torrentInfo.pieceLength()} pieces=${torrentInfo.numPieces()} " +
                    "offset=${files.fileOffset(fileIndex)} ${snapshot(added)}",
            )

            if (!onMetadata(fileSize)) return Result(false, entry.lastError)
            if (cancelled()) return Result(false, null)

            val resuming = partFile.exists() && partFile.length() > 0

            // Stay in the swarm. Pausing here used to send a tracker "stopped", drop
            // peers, and give rarest-first a head start on the middle of the file.
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
                .onFailure {
                    Log.w(TAG, "renameFile failed: ${it.message}")
                    DiagLog.w("renameFile failed: ${it.message}")
                }
            DiagLog.t(
                "priorities file$fileIndex=${added.filePriority(fileIndex)} " +
                    "partExists=${partFile.exists()} partLen=${partFile.length()}",
            )

            if (resuming) {
                Log.i(TAG, "rechecking existing ${partFile.name} (${partFile.length()} bytes)")
                report(entry, "checking the part already on disk…")
                runCatching { added.forceRecheck() }
            }

            runCatching { added.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }

            val pieceLength = torrentInfo.pieceLength().toLong()
            if (pieceLength <= 0) return Result(false, "Torrent piece size was 0")
            val fileOffset = files.fileOffset(fileIndex)
            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
            runCatching { added.setSequentialRange(firstPiece, lastPiece) }
            DiagLog.t(
                "sequential flags=${flagNames(added)} first=$firstPiece last=$lastPiece " +
                    "head=${minOf(lastPiece, firstPiece + HEAD_PIECES - 1)} " +
                    "cue=$lastPiece pieceLen=$pieceLength",
            )

            val current = Active(
                cacheKey = entry.cacheKey,
                handle = added,
                fileOffset = fileOffset,
                fileSize = fileSize,
                pieceLength = pieceLength,
                firstPiece = firstPiece,
                lastPiece = lastPiece,
                numPieces = torrentInfo.numPieces(),
                cursor = firstPiece,
            )
            active = current
            applyPlaybackPriorities(current)

            DiagLog.t("pump start firstPiece=$firstPiece lastPiece=$lastPiece offset=$fileOffset")
            val result = pump(
                entry,
                added,
                firstPiece,
                lastPiece,
                fileOffset,
                fileSize,
                pieceLength,
                cancelled,
            )
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
            entry.verifiedBytes = finalFile.length()
            entry.totalBytes = finalFile.length()
            entry.lastError = null
            Log.i(TAG, "complete ${entry.cacheKey} (${finalFile.length()} bytes)")
            DiagLog.i("complete ${entry.cacheKey} (${finalFile.length()} bytes)")
            return Result(true)
        } catch (e: Throwable) {
            Log.e(TAG, "torrent failed for ${entry.cacheKey}: ${e.message}", e)
            DiagLog.e("torrent failed ${entry.cacheKey}: ${e.message}")
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
        DiagLog.t("removing leftover ${sha1.toHex()}")
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
        var waitTick = 0
        while (System.currentTimeMillis() < deadline) {
            if (cancelled() || !handle.isValid) return null

            val status = runCatching { handle.status() }.getOrNull()
            if (status?.hasMetadata() == true) {
                val info = runCatching { handle.torrentFile() }.getOrNull()
                if (info != null && info.isValid) return info
            }

            val peers = status?.numPeers() ?: 0
            report(entry, if (peers > 0) "asking $peers peers for the file list…" else "looking for peers…")
            waitTick++
            if (waitTick == 1 || waitTick % 4 == 0) DiagLog.t("metadata wait ${snapshot(handle)}")

            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return null
            }
        }
        DiagLog.w("metadata timeout ${snapshot(handle)}")
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
        var tick = 0
        val startedAt = System.currentTimeMillis()

        while (true) {
            if (cancelled()) return Result(false, null)
            if (!handle.isValid) return Result(false, "Torrent was removed from the session")

            val status = runCatching { handle.status(status_flags_t.all()) }.getOrNull()
            val haveCopy = copyHaveBits(status?.pieces(), lastPiece)
            if (haveCopy != null) {
                while (cursor <= lastPiece && cursor < haveCopy.size && haveCopy[cursor]) cursor++
            } else {
                while (cursor <= lastPiece && runCatching { handle.havePiece(cursor) }.getOrDefault(false)) {
                    cursor++
                }
            }
            active?.let { current ->
                if (current.handle === handle) {
                    current.cursor = cursor
                    if (haveCopy != null) current.haveBits = haveCopy
                    maybeApplyPriorities(current)
                }
            }
            val readableEnd = minOf(cursor.toLong() * pieceLength, fileOffset + fileSize)
            val contiguous = (readableEnd - fileOffset).coerceIn(0, fileSize)
            val verified = status?.totalWantedDone() ?: 0L

            entry.downloadedBytes = contiguous
            entry.verifiedBytes = verified
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
                    contiguous < 4L * 1024 * 1024 ->
                        "$peers peers · ${verified / (1024 * 1024)} MB in · opening the file"
                    else -> null
                },
            )

            tick++
            val now = System.currentTimeMillis()
            if (DiagLog.enabled && (tick <= 8 || tick % 3 == 0)) {
                DiagLog.t(
                    "pump tick=$tick contig=$contiguous cursor=$cursor " +
                        "have[$cursor..]=${haveWindow(haveCopy, cursor)} " +
                        "${snapshot(handle)}",
                )
            }

            val idleMs = now - lastProgressAt
            if (contiguous <= 0 && now - startedAt > FIRST_BYTES_TIMEOUT_MS) {
                return Result(
                    false,
                    "Torrent sent no usable data in 5 min " +
                        "($state, $peers peers, $seeds seeds, ${verified / (1024 * 1024)} MB verified)",
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

        val first = ((current.fileOffset + offset) / current.pieceLength).toInt()
        val have = current.haveBits
        if (have != null) {
            if (first < 0 || first >= have.size || !have[first]) return offset
            var piece = first + 1
            val stopAt = minOf(current.lastPiece, first + LOOKAHEAD_PIECES, have.size - 1)
            while (piece <= stopAt && have[piece]) piece++
            val end = piece.toLong() * current.pieceLength - current.fileOffset
            return end.coerceIn(offset, current.fileSize)
        }

        val handle = current.handle
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
     * Notes where the player is reading. The pump applies piece priorities — this must not
     * call into libtorrent on the HTTP thread (deadlines on a busy USB stick blocked serving
     * for tens of seconds and froze the playable cursor).
     */
    fun requestOffset(cacheKey: String, offset: Long) {
        val current = active ?: return
        if (current.cacheKey != cacheKey) return
        if (offset < 0 || offset >= current.fileSize) return
        current.playhead = offset
    }

    /** True when the first piece of the video file is on disk — enough for a real container header. */
    fun hasOpeningPiece(cacheKey: String): Boolean {
        val current = active ?: return false
        if (current.cacheKey != cacheKey) return false
        val have = current.haveBits ?: return false
        val first = current.firstPiece
        return first >= 0 && first < have.size && have[first]
    }

    /** True when the opening piece and the MKV cue piece are both on disk. */
    fun isReadyForPlayer(cacheKey: String): Boolean {
        val current = active ?: return false
        if (current.cacheKey != cacheKey) return false
        val have = current.haveBits ?: return false
        val first = current.firstPiece
        val last = current.lastPiece
        if (first < 0 || last < 0 || first >= have.size || last >= have.size) return false
        return have[first] && have[last]
    }

    /**
     * Re-mark the opening window and the MKV cue piece when the cursor or playhead moves.
     * One `prioritizePieces` call; the old per-piece deadline loop stalled the pump on USB.
     */
    private fun maybeApplyPriorities(current: Active) {
        val nearEnd = current.playhead > current.fileSize - TAIL_PROBE_BYTES
        val startReady = current.cursor >= current.firstPiece + 8
        val seekPiece = ((current.fileOffset + current.playhead) / current.pieceLength).toInt()
        val seek = if (nearEnd && !startReady) current.cursor else seekPiece
        if (
            current.cursor - current.lastPrioCursor < HEAD_PIECES / 2 &&
            current.lastPrioCursor != Int.MIN_VALUE &&
            seek == current.lastPrioSeek
        ) {
            return
        }
        current.lastPrioCursor = current.cursor
        current.lastPrioSeek = seek
        applyPlaybackPriorities(current, seek)
    }

    private fun applyPlaybackPriorities(current: Active, seek: Int = current.cursor) {
        val prios = Priority.array(Priority.IGNORE, current.numPieces)
        for (i in current.firstPiece..current.lastPiece) {
            prios[i] = Priority.DEFAULT
        }
        val headEnd = minOf(current.lastPiece, current.cursor + HEAD_PIECES - 1)
        for (i in current.cursor..headEnd) prios[i] = Priority.TOP_PRIORITY
        prios[current.lastPiece] = Priority.TOP_PRIORITY
        if (seek > current.cursor + 4) {
            val seekEnd = minOf(current.lastPiece, seek + SEEK_PIECE_WINDOW - 1)
            for (i in seek..seekEnd) prios[i] = Priority.TOP_PRIORITY
        }
        runCatching { current.handle.prioritizePieces(prios) }
        runCatching { current.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
    }

    private fun copyHaveBits(bits: PieceIndexBitfield?, lastPiece: Int): BooleanArray? {
        if (bits == null || bits.isEmpty()) return null
        val n = minOf(lastPiece + 1, bits.size())
        if (n <= 0) return null
        return BooleanArray(n) { i -> runCatching { bits.getBit(i) }.getOrDefault(false) }
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

    private fun snapshot(handle: TorrentHandle): String {
        val status = runCatching { handle.status() }.getOrNull()
            ?: return "invalid=${!handle.isValid}"
        val err = runCatching { status.errorCode()?.message }.getOrNull().orEmpty()
        return buildString {
            append("state=").append(status.state())
            append(" flags=").append(flagNames(handle))
            append(" peers=").append(status.numPeers())
            append("/").append(status.numSeeds())
            append(" listed=").append(status.listPeers())
            append("/").append(status.listSeeds())
            append(" conn=").append(status.numConnections())
            append(" cand=").append(status.connectCandidates())
            append(" wanted=").append(status.totalWantedDone())
            append("/").append(status.totalWanted())
            append(" payload=").append(status.totalPayloadDownload())
            append(" rate=").append(status.downloadPayloadRate())
            append(" pieces=").append(status.numPieces())
            append(" incoming=").append(status.hasIncoming())
            append(" tracker=").append(status.currentTracker().ifBlank { "-" })
            if (err.isNotBlank() && !err.equals("Success", ignoreCase = true) && !err.equals("No error", ignoreCase = true)) {
                append(" err=").append(err)
            }
        }
    }

    private fun flagNames(handle: TorrentHandle): String {
        val flags = runCatching { handle.status().flags() }.getOrNull() ?: return "?"
        val names = mutableListOf<String>()
        fun bit(name: String, flag: org.libtorrent4j.swig.torrent_flags_t) {
            if (runCatching { flags.and_(flag).non_zero() }.getOrDefault(false)) names += name
        }
        bit("seq", TorrentFlags.SEQUENTIAL_DOWNLOAD)
        bit("paused", TorrentFlags.PAUSED)
        bit("auto", TorrentFlags.AUTO_MANAGED)
        bit("upload", TorrentFlags.UPLOAD_MODE)
        return names.joinToString("|").ifBlank { "none" }
    }

    private fun haveWindow(have: BooleanArray?, firstPiece: Int): String {
        return buildString {
            for (i in 0 until 8) {
                val piece = firstPiece + i
                val bit = have != null && piece < have.size && have[piece]
                append(if (bit) '1' else '0')
            }
        }
    }

    private fun isNoisyAlert(type: AlertType): Boolean {
        val name = type.name
        return name.contains("STATS") ||
            name.contains("LOG") ||
            name.startsWith("DHT") ||
            name == "PIECE_FINISHED" ||
            name == "BLOCK_FINISHED" ||
            name == "BLOCK_DOWNLOADING" ||
            name == "BLOCK_TIMEOUT" ||
            name == "BLOCK_UPLOADED" ||
            name == "INCOMING_REQUEST" ||
            name == "INCOMING_CONNECTION" ||
            name == "REQUEST_DROPPED" ||
            name == "PEER_CONNECT" ||
            name == "PEER_DISCONNECTED" ||
            name == "PEER_SNUBBED" ||
            name == "PEER_UNSNUBBED" ||
            name == "PEER_BLOCKED" ||
            name == "PERFORMANCE" ||
            name == "UNWANTED_BLOCK"
    }
}
