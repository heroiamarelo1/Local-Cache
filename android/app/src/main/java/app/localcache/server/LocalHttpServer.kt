package app.localcache.server

import android.content.Context
import android.util.Log
import app.localcache.BuildConfig
import app.localcache.Prefs
import app.localcache.config.AddonConfig
import app.localcache.model.StreamPick
import app.localcache.storage.CacheRegistry
import app.localcache.storage.DiskQuota
import app.localcache.storage.DownloadEngine
import app.localcache.storage.LocalLibrary
import app.localcache.storage.PlaybackStatus
import app.localcache.storage.StorageMode
import app.localcache.stream.StreamDiagnostics
import app.localcache.stream.StreamLabelFormatter
import app.localcache.stream.StreamResponseBuilder
import app.localcache.stream.TvStreamOrder
import app.localcache.stream.UpstreamFetcher
import fi.iki.elonen.NanoHTTPD
import java.net.ServerSocket
import java.net.URLDecoder

class LocalHttpServer(
    private val appContext: Context,
    private val lanHost: String,
    port: Int,
) : NanoHTTPD("0.0.0.0", port) {

    private val fetcher = UpstreamFetcher(appContext)
    private var started = false

    init {
        setServerSocketFactory {
            ServerSocket().apply {
                reuseAddress = true
            }
        }
    }

    fun startServer() {
        if (started && isAlive) {
            Log.i(TAG, "Already listening on port $listeningPort")
            return
        }

        start(SOCKET_READ_TIMEOUT, false)
        started = true
        Log.i(TAG, "Listening on http://$lanHost:$listeningPort")
        runCatching { LocalLibrary.rehydrate(appContext) }
        runCatching { DiskQuota.trim(appContext) }
    }

    override fun stop() {
        try {
            super.stop()
        } finally {
            started = false
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            handle(session)
        } catch (e: Exception) {
            Log.e(TAG, "request failed: ${session.method} ${session.uri}", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, """{"error":"${e.message}"}""")
        }
    }

    private fun handle(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method ?: Method.GET

        // Stremio / WebView CORS preflight
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "").apply {
                addCorsHeaders()
            }
        }

        when {
            uri == "/health" -> {
                val host = Prefs.lanHost(appContext)
                val publicHost = Prefs.publicHost(appContext)
                val cfg = AddonConfig.load(appContext)
                val body = org.json.JSONObject()
                    .put("ok", true)
                    .put("lanHost", host)
                    .put("publicHost", publicHost)
                    .put("port", listeningPort)
                    .put("stremioInstall", Prefs.stremioInstallUrl(appContext))
                    .put("wuplayInstall", Prefs.wuplayInstallUrl(appContext))
                    .put("settings", "http://$host:$listeningPort/settings")
                    .put("upstreamsConfigured", cfg.hasAnyUpstream())
                    .put("streamQuality", cfg.streamQuality)
                    .put("resultMode", cfg.resultMode)
                    .put("debridServices", org.json.JSONArray(cfg.debridServices))
                    .put("partialPlay", true)
                    .put(
                        "note",
                        if (BuildConfig.WUPLAY_MODE) {
                            if (publicHost != null) {
                                "WuPlay install uses public IP (router forward :$listeningPort → $host). Phone settings stay on LAN."
                            } else {
                                "Set public IP in the app so config.wuplay.app can Preview. Phone /settings uses LAN."
                            }
                        } else {
                            "Open this URL from a phone on the same Wi‑Fi. No router changes needed."
                        },
                    )
                return jsonResponse(Response.Status.OK, body.toString())
            }

            uri == "/settings" || uri == "/settings/" -> {
                return SettingsPage.serve(appContext, session)
            }

            uri == "/status" -> {
                val body = PlaybackStatus.snapshot()
                    .put("storageMode", Prefs.storageMode(appContext))
                    .put("cacheDir", Prefs.cacheDirPath(appContext))
                    .put("internal", StorageMode.isInternal(appContext))
                    .put("quotaSummary", DiskQuota.summary(appContext))
                    .put("roomBytes", StorageMode.roomBytesForNewFile(appContext))
                return jsonResponse(Response.Status.OK, body.toString())
            }

            uri == "/cache" -> {
                val cacheDir = Prefs.cacheDirPath(appContext) ?: "not set"
                val entries = CacheRegistry.snapshot()
                val body = org.json.JSONObject()
                    .put("cacheDir", cacheDir)
                    .put("storageMode", Prefs.storageMode(appContext))
                    .put("quota", org.json.JSONObject(DiskQuota.asJson(appContext)))
                    .put("quotaSummary", DiskQuota.summary(appContext))
                    .put("config", AddonConfig.toJson(AddonConfig.load(appContext)))
                    .put("entries", org.json.JSONArray(entries.map { org.json.JSONObject(it) }))
                return jsonResponse(Response.Status.OK, body.toString())
            }

            uri == "/cancel" -> {
                val keep = session.parameters?.get("keep")?.firstOrNull() == "1"
                val message = DownloadEngine.cancelActive(deletePartial = !keep)
                Log.i(TAG, "cancel requested: $message")
                return jsonResponse(
                    Response.Status.OK,
                    org.json.JSONObject()
                        .put("ok", true)
                        .put("message", message)
                        .put("quotaSummary", DiskQuota.summary(appContext))
                        .toString(),
                )
            }

            uri == "/test" -> {
                Log.i(TAG, "diagnostics /test")
                val report = StreamDiagnostics.run(appContext, fetcher, listeningPort)
                return jsonResponse(Response.Status.OK, report.toString())
            }

            uri == "/manifest.json" -> {
                val cfg = AddonConfig.load(appContext)
                val qualityNote = if (cfg.is4kSound()) "4K sound" else "1080p"
                val installUrl = Prefs.clientInstallUrl(appContext)
                val appName = if (BuildConfig.WUPLAY_MODE) "Local Cache WuPlay" else "Local Cache"
                val descClient = if (BuildConfig.WUPLAY_MODE) "WuPlay" else "Stremio"
                return jsonResponse(
                    Response.Status.OK,
                    """
                    {
                      "id": "${BuildConfig.ADDON_ID}",
                      "version": "${BuildConfig.VERSION_NAME}",
                      "name": "$appName",
                      "description": "Local $descClient cache · $qualityNote · install $installUrl",
                      "resources": [
                        { "name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt"] }
                      ],
                      "types": ["movie", "series"],
                      "catalogs": [],
                      "behaviorHints": { "configurable": false, "configurationRequired": false }
                    }
                    """.trimIndent(),
                )
            }

            uri.startsWith("/stream/") && uri.endsWith(".json") -> {
                val parts = uri.removePrefix("/stream/").removeSuffix(".json").split("/", limit = 2)
                if (parts.size != 2) return jsonResponse(Response.Status.BAD_REQUEST, """{"error":"Bad path"}""")
                val type = URLDecoder.decode(parts[0], "UTF-8")
                val id = URLDecoder.decode(parts[1], "UTF-8")
                Log.i(TAG, "stream request $type/$id")

                val cfg = AddonConfig.load(appContext)

                val onDrive = LocalLibrary.forTitle(appContext, type, id).map { item ->
                    CacheRegistry.registerLocal(item)
                    StreamPick(
                        LocalLibrary.toStreamItem(item),
                        if (item.complete) "on_drive" else "on_drive_partial",
                    )
                }

                if (!cfg.hasAnyUpstream() && onDrive.isEmpty()) {
                    val json = StreamResponseBuilder.configureFirstJson(
                        lanHost = callerHost(session) ?: lanHost,
                        port = listeningPort,
                    )
                    RequestLog.record(type, id, session.headers["host"], 0, 0)
                    return jsonResponse(Response.Status.OK, json)
                }

                val all = if (cfg.hasAnyUpstream()) {
                    runCatching { fetcher.fetchAll(type, id) }
                        .onFailure { Log.w(TAG, "upstreams failed for $type/$id: ${it.message}") }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                val maxFitBytes = if (StorageMode.isInternal(appContext)) {
                    StorageMode.roomBytesForNewFile(appContext)
                } else {
                    0L
                }
                val built = TvStreamOrder.buildOrdered(
                    allStreams = all,
                    onDrive = onDrive,
                    quality = cfg.streamQuality,
                    enabledDebrid = cfg.debridServices,
                    completeResults = cfg.isCompleteResults(),
                    maxFitBytes = maxFitBytes,
                )
                Log.i(
                    TAG,
                    "stream $type/$id raw=${built.rawCount} strict=${built.strictCount} out=${built.picks.size} fallback=${built.usedFallback}",
                )
                built.picks.forEach { pick ->
                    if (pick.slot == "fits_none") return@forEach
                    CacheRegistry.register(pick.stream, type, id)
                    CacheRegistry.attachCachePath(appContext, pick.stream.cacheKey)
                }
                val topName = built.picks.firstOrNull()?.stream?.let {
                    StreamLabelFormatter.releaseName(it)
                }
                RequestLog.record(
                    type,
                    id,
                    session.headers["host"],
                    results = built.picks.size,
                    onDrive = onDrive.size,
                    raw = built.rawCount,
                    topFile = topName,
                )

                // Stremio: prefer loopback. WuPlay: match Host (often public IP via port forward).
                val videoHost = resolveVideoHost(session)
                val storageLabel = if (StorageMode.isInternal(appContext)) "device" else "USB"
                val json = StreamResponseBuilder.toJson(
                    lanHost = lanHost,
                    port = listeningPort,
                    build = built,
                    progressOf = { key ->
                        CacheRegistry.refreshFromDisk(key)
                        CacheRegistry.progress(key)
                    },
                    statusOf = { key -> CacheRegistry.get(key)?.status },
                    videoHost = videoHost,
                    enabledDebrid = cfg.debridServices,
                    configured = cfg.hasAnyUpstream(),
                    storageLabel = storageLabel,
                )
                Log.i(TAG, "stream response bytes=${json.length} videoHost=$videoHost sample=${json.take(180)}")
                return jsonResponse(Response.Status.OK, json)
            }

            uri.startsWith("/video/") -> {
                val cacheKey = URLDecoder.decode(uri.removePrefix("/video/"), "UTF-8")
                return VideoHandler.serve(appContext, session, cacheKey, headOnly = method == Method.HEAD)
            }
        }

        return jsonResponse(Response.Status.NOT_FOUND, """{"error":"Not found"}""")
    }

    private fun callerHost(session: IHTTPSession): String? {
        val host = session.headers["host"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // Keep IPv6 literals like [::1]:7100 intact; only strip :port for IPv4/hostname.
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end > 0) return host.substring(0, end + 1)
        }
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.indexOf(':') == colon) {
            return host.substring(0, colon).takeIf { it.isNotBlank() }
        }
        return host
    }

    private fun resolveVideoHost(session: IHTTPSession): String {
        val caller = callerHost(session)?.trim().orEmpty()
        if (BuildConfig.WUPLAY_MODE) {
            if (caller.isNotBlank() &&
                caller != "127.0.0.1" &&
                caller != "localhost" &&
                caller != "[::1]" &&
                caller != "::1"
            ) {
                return caller.removePrefix("[").removeSuffix("]")
            }
            return Prefs.wuplayHost(appContext)
        }
        if (caller == "127.0.0.1" || caller == "localhost" || caller == "[::1]" || caller == "::1") {
            return "127.0.0.1"
        }
        if (caller.isNotBlank()) return caller.removePrefix("[").removeSuffix("]")
        return "127.0.0.1"
    }

    private fun Response.addCorsHeaders() {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Accept, Range")
        addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
    }

    private fun jsonResponse(status: Response.Status, body: String): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addCorsHeaders()
            // Stremio otherwise keeps an old stream list after USB ↔ internal switches.
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
            addHeader("Pragma", "no-cache")
        }
    }

    companion object {
        private const val TAG = "LocalHttpServer"
    }
}
