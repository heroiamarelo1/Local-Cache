package app.localcache.config

import android.content.Context
import android.util.Log
import app.localcache.Prefs
import app.localcache.model.Upstream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User configuration for Local Cache.
 *
 * Lives primarily in [Prefs], with a JSON copy next to the movies folder when possible
 * (USB root / USB cache dir, or internal LocalCache folder).
 */
object AddonConfig {
    private const val TAG = "AddonConfig"

    const val CONFIG_FILE_NAME = "local-cache-config.json"
    const val README_FILE_NAME = "local-cache-config.README.txt"

    const val QUALITY_1080P = "1080p"
    const val QUALITY_4K_SOUND = "4k_sound"

    /** Answer sooner: shorter wait, fewer streams. Default. */
    const val RESULT_FAST = "fast"
    /** Race upstreams; hard ~3s for a debrid-cached hit, else fall back to fast. */
    const val RESULT_FASTEST = "fastest"
    /** Wait for all upstreams and return more streams. */
    const val RESULT_COMPLETE = "complete"

    /** @deprecated Use [RESULT_FAST]. Kept so old USB configs still load. */
    const val RESULT_RELIABLE = "reliable"

    val ALL_DEBRID_SERVICES = listOf(
        "AllDebrid",
        "TorBox",
        "RealDebrid",
        "Premiumize",
        "DebridLink",
        "EasyDebrid",
        "Offcloud",
        "Putio",
    )

    data class Snapshot(
        /** One Torrentio configure URL per debrid (Torrentio only supports one debrid per manifest). */
        val torrentioManifestUrls: List<String> = emptyList(),
        /** Public / ElfHosted Comet manifests (multiple allowed). */
        val cometManifestUrls: List<String> = emptyList(),
        /** Optional self-hosted Comet on LAN (PC/NAS). Independent of [cometManifestUrls]. */
        val localCometManifestUrls: List<String> = emptyList(),
        val debridServices: List<String>,
        val streamQuality: String,
        val cacheMaxGb: Int,
        val resultMode: String = RESULT_FAST,
    ) {
        /** First Torrentio URL — for simple TV fields / legacy display. */
        val torrentioManifestUrl: String get() = torrentioManifestUrls.firstOrNull().orEmpty()
        val cometManifestUrl: String get() = cometManifestUrls.firstOrNull().orEmpty()
        val localCometManifestUrl: String get() = localCometManifestUrls.firstOrNull().orEmpty()

        fun hasAnyUpstream(): Boolean =
            torrentioManifestUrls.any { isManifestUrl(it) } ||
                cometManifestUrls.any { isManifestUrl(it) } ||
                localCometManifestUrls.any { isManifestUrl(it) }

        fun upstreams(): List<Upstream> = buildList {
            addNamedUpstreams("Torrentio", torrentioManifestUrls)
            addNamedUpstreams("Comet", cometManifestUrls)
            addNamedUpstreams("Comet Local", localCometManifestUrls)
        }

        private fun MutableList<Upstream>.addNamedUpstreams(base: String, urls: List<String>) {
            val valid = normalizeUrlList(urls).filter { isManifestUrl(it) }
            valid.forEachIndexed { index, url ->
                val name = if (valid.size == 1) base else "$base ${index + 1}"
                add(Upstream(name, url))
            }
        }

        fun is4kSound(): Boolean = streamQuality == QUALITY_4K_SOUND

        fun isCompleteResults(): Boolean = resultMode == RESULT_COMPLETE

        fun isFastestResults(): Boolean = resultMode == RESULT_FASTEST

        fun upstreamTimeoutSeconds(): Long = when {
            isCompleteResults() -> 30L
            isFastestResults() -> 3L
            else -> 8L
        }

        fun maxStreamsForClient(): Int = when {
            isCompleteResults() -> 200
            isFastestResults() -> 8
            else -> 25
        }
    }

    fun normalizeUrlList(urls: Collection<String>): List<String> =
        urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun normalizeResultMode(mode: String?): String =
        when (mode?.trim()?.lowercase()) {
            RESULT_COMPLETE -> RESULT_COMPLETE
            RESULT_FASTEST -> RESULT_FASTEST
            RESULT_FAST, RESULT_RELIABLE, "", null -> RESULT_FAST
            else -> RESULT_FAST
        }

    fun isManifestUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        return u.contains("manifest.json", ignoreCase = true)
    }

    fun load(context: Context): Snapshot {
        val services = Prefs.debridServices(context)
        val quality = Prefs.streamQuality(context).let {
            if (it == QUALITY_4K_SOUND) QUALITY_4K_SOUND else QUALITY_1080P
        }
        return Snapshot(
            torrentioManifestUrls = Prefs.torrentioManifestUrls(context),
            cometManifestUrls = Prefs.cometManifestUrls(context),
            localCometManifestUrls = Prefs.localCometManifestUrls(context),
            debridServices = services.mapNotNull { name ->
                ALL_DEBRID_SERVICES.firstOrNull { it.equals(name, ignoreCase = true) }
            }.distinct(),
            streamQuality = quality,
            cacheMaxGb = Prefs.cacheMaxGb(context),
            resultMode = normalizeResultMode(Prefs.resultMode(context)),
        )
    }

    fun save(context: Context, snapshot: Snapshot, writeUsb: Boolean = true): String {
        val cleaned = snapshot.copy(
            torrentioManifestUrls = normalizeUrlList(snapshot.torrentioManifestUrls),
            cometManifestUrls = normalizeUrlList(snapshot.cometManifestUrls),
            localCometManifestUrls = normalizeUrlList(snapshot.localCometManifestUrls),
            debridServices = snapshot.debridServices
                .mapNotNull { name ->
                    ALL_DEBRID_SERVICES.firstOrNull { it.equals(name, ignoreCase = true) }
                }
                .distinct(),
            streamQuality = if (snapshot.streamQuality == QUALITY_4K_SOUND) {
                QUALITY_4K_SOUND
            } else {
                QUALITY_1080P
            },
            cacheMaxGb = snapshot.cacheMaxGb.coerceIn(1, 4096),
            resultMode = normalizeResultMode(snapshot.resultMode),
        )

        Prefs.setTorrentioManifestUrls(context, cleaned.torrentioManifestUrls)
        Prefs.setCometManifestUrls(context, cleaned.cometManifestUrls)
        Prefs.setLocalCometManifestUrls(context, cleaned.localCometManifestUrls)
        Prefs.setDebridServices(context, cleaned.debridServices)
        Prefs.setStreamQuality(context, cleaned.streamQuality)
        Prefs.setCacheMaxGb(context, cleaned.cacheMaxGb)
        Prefs.setResultMode(context, cleaned.resultMode)

        // Config changes should invalidate the 15‑minute upstream result cache.
        runCatching { app.localcache.stream.UpstreamFetcher(context).clearCache() }

        if (writeUsb) {
            val written = writeConfigSafely(context, cleaned)
            Prefs.setConfigStatus(
                context,
                written?.let { "Saved · ${it.absolutePath}" }
                    ?: "Saved on TV (could not write JSON beside movies — prefs updated)",
            )
            return Prefs.configStatus(context)!!
        }

        Prefs.setConfigStatus(context, "Saved on TV")
        return Prefs.configStatus(context)!!
    }

    fun toJson(snapshot: Snapshot): JSONObject = JSONObject()
        .put("torrentioManifestUrls", JSONArray(snapshot.torrentioManifestUrls))
        .put("cometManifestUrls", JSONArray(snapshot.cometManifestUrls))
        .put("localCometManifestUrls", JSONArray(snapshot.localCometManifestUrls))
        // Legacy singular keys (first URL) so older editors still see something.
        .put("torrentioManifestUrl", snapshot.torrentioManifestUrl)
        .put("cometManifestUrl", snapshot.cometManifestUrl)
        .put("localCometManifestUrl", snapshot.localCometManifestUrl)
        .put("debridServices", JSONArray(snapshot.debridServices))
        .put("streamQuality", snapshot.streamQuality)
        .put("cacheMaxGb", snapshot.cacheMaxGb)
        .put("resultMode", snapshot.resultMode)

    fun fromJson(obj: JSONObject): Snapshot {
        val services = mutableListOf<String>()
        val arr = obj.optJSONArray("debridServices")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val name = arr.optString(i).trim()
                if (name.isNotBlank()) services.add(name)
            }
        }
        return Snapshot(
            torrentioManifestUrls = readUrlListFromJson(obj, "torrentioManifestUrls", "torrentioManifestUrl"),
            cometManifestUrls = readUrlListFromJson(obj, "cometManifestUrls", "cometManifestUrl"),
            localCometManifestUrls = readUrlListFromJson(obj, "localCometManifestUrls", "localCometManifestUrl"),
            debridServices = services,
            streamQuality = obj.optString("streamQuality", QUALITY_1080P),
            cacheMaxGb = obj.optInt("cacheMaxGb", Prefs.DEFAULT_CACHE_MAX_GB),
            resultMode = normalizeResultMode(obj.optString("resultMode", RESULT_FAST)),
        )
    }

    private fun readUrlListFromJson(obj: JSONObject, arrayKey: String, singularKey: String): List<String> {
        val out = mutableListOf<String>()
        val arr = obj.optJSONArray(arrayKey)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).trim()
                if (u.isNotBlank()) out.add(u)
            }
        }
        val singular = obj.optString(singularKey, "").trim()
        if (singular.isNotBlank() && out.none { it.equals(singular, ignoreCase = true) }) {
            out.add(0, singular)
        }
        return normalizeUrlList(out)
    }

    fun defaultTemplate(): Snapshot = Snapshot(
        torrentioManifestUrls = emptyList(),
        cometManifestUrls = emptyList(),
        localCometManifestUrls = emptyList(),
        debridServices = emptyList(),
        streamQuality = QUALITY_1080P,
        cacheMaxGb = Prefs.DEFAULT_CACHE_MAX_GB,
        resultMode = RESULT_FAST,
    )

    /**
     * Places to look for / write the config. USB root is preferred (easy to edit on a PC),
     * but Android 10+ often blocks writing there — same reason movies go under Android/data.
     * Never throw: a failed root write used to crash Choose USB.
     */
    fun configCandidates(usbRoot: File, writableDir: File?): List<File> = buildList {
        add(File(usbRoot, CONFIG_FILE_NAME))
        if (writableDir != null) {
            add(File(writableDir, CONFIG_FILE_NAME))
            writableDir.parentFile?.let { add(File(it, CONFIG_FILE_NAME)) }
        }
    }.distinctBy { it.absolutePath }

    fun findExistingConfig(usbRoot: File, writableDir: File?): File? =
        configCandidates(usbRoot, writableDir).firstOrNull { it.isFile }

    /** Try USB root first, then the app-writable folder on the stick. */
    fun writeConfigSafely(context: Context, snapshot: Snapshot): File? {
        val usbRoot = Prefs.usbRootPath(context)?.let { File(it) }
        val writable = Prefs.cacheDirPath(context)?.let { File(it) }
        val targets = configCandidates(
            usbRoot ?: return writableWrite(writable, snapshot),
            writable,
        )
        for (target in targets) {
            val ok = runCatching {
                target.parentFile?.mkdirs()
                target.writeText(toJson(snapshot).toString(2) + "\n")
                true
            }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "wrote config to ${target.absolutePath}")
                return target
            }
            Log.w(TAG, "could not write ${target.absolutePath}")
        }
        return null
    }

    private fun writableWrite(writable: File?, snapshot: Snapshot): File? {
        if (writable == null) return null
        val target = File(writable, CONFIG_FILE_NAME)
        return runCatching {
            writable.mkdirs()
            target.writeText(toJson(snapshot).toString(2) + "\n")
            target
        }.getOrNull()
    }

    private fun writeReadmeSafely(context: Context, dir: File) {
        val readme = File(dir, README_FILE_NAME)
        if (readme.exists()) return
        runCatching {
            dir.mkdirs()
            context.assets.open(README_FILE_NAME).bufferedReader().use { reader ->
                readme.writeText(reader.readText())
            }
        }.recoverCatching {
            readme.writeText(fallbackReadme())
        }.onFailure {
            Log.w(TAG, "could not write README: ${it.message}")
        }
    }

    /**
     * Import config after the user picks a drive.
     * Reads from USB root if present (PC-edited file); otherwise creates a template in a
     * folder Android will actually let us write.
     */
    fun importFromUsb(context: Context, usbRoot: File, writableDir: File): String {
        return try {
            Prefs.setUsbRootPath(context, usbRoot.absolutePath)

            val existing = findExistingConfig(usbRoot, writableDir)
            if (existing == null) {
                val created = writeConfigSafely(context, defaultTemplate())
                writeReadmeSafely(context, writableDir)
                runCatching { writeReadmeSafely(context, usbRoot) }
                val msg = if (created != null) {
                    "Created $CONFIG_FILE_NAME at ${created.absolutePath}. " +
                        "Edit it on a PC (USB root is easiest) or open /settings, then Choose USB again."
                } else {
                    "Could not create $CONFIG_FILE_NAME on this drive. " +
                        "On a PC, create it on the USB root, or use Edit config on TV / /settings."
                }
                Prefs.setConfigStatus(context, msg)
                return msg
            }

            val snapshot = fromJson(JSONObject(existing.readText()))
            save(context, snapshot, writeUsb = false)
            val upstreams = snapshot.upstreams().size
            val msg = if (upstreams == 0) {
                "Imported ${existing.name} — add Torrentio/Comet manifest URLs (edit file or /settings)"
            } else {
                "Imported ${existing.name} — $upstreams upstream(s), quality=${snapshot.streamQuality}"
            }
            Prefs.setConfigStatus(context, msg)
            msg
        } catch (e: Exception) {
            val msg = "USB config failed: ${e.message}"
            Log.e(TAG, msg, e)
            Prefs.setConfigStatus(context, msg)
            msg
        }
    }

    /** @deprecated Use [importFromUsb] with the writable cache dir. */
    fun importFromUsbRoot(context: Context, usbRoot: File): String {
        val writable = Prefs.cacheDirPath(context)?.let { File(it) }
            ?: File(usbRoot, "Android/data/${context.packageName}/files/LocalCache")
        return importFromUsb(context, usbRoot, writable)
    }

    fun summaryLine(context: Context): String {
        val s = load(context)
        val upstreamLabels = s.upstreams().map { upstream ->
            val host = upstreamHost(upstream.manifestUrl) ?: "?"
            "${upstream.name} ($host)"
        }
        val status = Prefs.configStatus(context)
        return buildString {
            append("Quality: ${if (s.is4kSound()) "4K high quality sound" else "1080p (recommended)"}")
            append(
                " · Results: " + when {
                    s.isCompleteResults() -> "complete"
                    s.isFastestResults() -> "fastest"
                    else -> "fast (default)"
                },
            )
            append(
                " · Debrid: " + if (s.debridServices.isEmpty()) {
                    "none selected"
                } else {
                    s.debridServices.joinToString(", ")
                },
            )
            if (upstreamLabels.isEmpty()) {
                append(" · Upstreams: not configured")
            } else {
                append(" · Upstreams: ${upstreamLabels.joinToString(", ")}")
            }
            status?.let { append("\nConfig: $it") }
        }
    }

    fun upstreamHost(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun fallbackReadme(): String = """
        Local Cache — configuration
        ===========================

        Edit local-cache-config.json in Notepad (or open http://TV_LAN_IP:${Prefs.DEFAULT_PORT}/settings).

        torrentioManifestUrls  (array)  — or legacy torrentioManifestUrl
          One FINAL manifest.json URL per debrid. Torrentio only supports one
          debrid service per configure link. For AllDebrid + TorBox, add two URLs.
          From https://torrentio.strem.fun/configure — not the configure page itself.

        cometManifestUrls  (array)  — or legacy cometManifestUrl
          Optional. ElfHosted / public Comet — one or more FINAL manifest.json URLs
          from https://comet.elfhosted.com/configure

        localCometManifestUrls  (array)  — or legacy localCometManifestUrl
          Optional. Self-hosted Comet on your LAN (PC/NAS), e.g.
          http://192.168.1.50:8000/.../manifest.json
          Same Wi‑Fi as the TV. Leave blank if unused.

        debridServices
          Check only the services you use (default: none):
          AllDebrid, TorBox, RealDebrid, Premiumize, DebridLink, EasyDebrid, Offcloud, Putio

        streamQuality
          "1080p"      — recommended, smaller files, safer on USB
          "4k_sound"   — 4K with great sound (needs fast USB + ~90 Mbps internet)

        cacheMaxGb
          Max space for cached movies on the USB (default 100).

        resultMode
          "fast"     — default. Answer sooner, fewer streams.
          "complete" — wait for all upstreams, more streams.

        After editing, Choose USB again in the app (or Save on /settings) to reload.
    """.trimIndent()
}
