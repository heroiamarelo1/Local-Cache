package app.localcache

import android.content.Context
import app.localcache.config.AddonConfig
import app.localcache.net.LanAddress
import app.localcache.net.LanIpDetector

object Prefs {
    /** Stremio build: 7100. WuPlay build: 7001 (see [BuildConfig.DEFAULT_PORT]). */
    val DEFAULT_PORT: Int get() = BuildConfig.DEFAULT_PORT

    /** Leaves headroom on a 128 GB stick, which formats to about 114 GiB. */
    const val DEFAULT_CACHE_MAX_GB = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(BuildConfig.PREFS_FILE, Context.MODE_PRIVATE)

    fun cacheMaxGb(context: Context): Int =
        prefs(context).getInt("cache_max_gb", DEFAULT_CACHE_MAX_GB)

    fun setCacheMaxGb(context: Context, gb: Int) {
        prefs(context).edit().putInt("cache_max_gb", gb.coerceIn(1, 4096)).apply()
    }

    fun serverPort(context: Context): Int =
        prefs(context).getInt("server_port", DEFAULT_PORT)

    fun setServerPort(context: Context, port: Int) {
        prefs(context).edit().putInt("server_port", port).apply()
    }

    fun torrentioManifestUrls(context: Context): List<String> =
        readUrlList(context, "torrentio_manifest_urls", "torrentio_manifest_url")

    fun setTorrentioManifestUrls(context: Context, urls: List<String>) {
        writeUrlList(context, "torrentio_manifest_urls", "torrentio_manifest_url", urls)
    }

    fun cometManifestUrls(context: Context): List<String> =
        readUrlList(context, "comet_manifest_urls", "comet_manifest_url")

    fun setCometManifestUrls(context: Context, urls: List<String>) {
        writeUrlList(context, "comet_manifest_urls", "comet_manifest_url", urls)
    }

    fun localCometManifestUrls(context: Context): List<String> =
        readUrlList(context, "local_comet_manifest_urls", "local_comet_manifest_url")

    fun setLocalCometManifestUrls(context: Context, urls: List<String>) {
        writeUrlList(context, "local_comet_manifest_urls", "local_comet_manifest_url", urls)
    }

    private fun readUrlList(context: Context, arrayKey: String, singularKey: String): List<String> {
        val out = mutableListOf<String>()
        val raw = prefs(context).getString(arrayKey, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val u = arr.optString(i).trim()
                    if (u.isNotBlank()) out.add(u)
                }
            }
        }
        val legacy = prefs(context).getString(singularKey, "")?.trim().orEmpty()
        if (legacy.isNotBlank() && out.none { it.equals(legacy, ignoreCase = true) }) {
            out.add(0, legacy)
        }
        return out.distinct()
    }

    private fun writeUrlList(
        context: Context,
        arrayKey: String,
        singularKey: String,
        urls: List<String>,
    ) {
        val cleaned = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val arr = org.json.JSONArray()
        cleaned.forEach { arr.put(it) }
        prefs(context).edit()
            .putString(arrayKey, arr.toString())
            .putString(singularKey, cleaned.firstOrNull().orEmpty())
            .apply()
    }

    fun streamQuality(context: Context): String =
        prefs(context).getString("stream_quality", AddonConfig.QUALITY_1080P)
            ?: AddonConfig.QUALITY_1080P

    fun setStreamQuality(context: Context, quality: String) {
        val value = if (quality == AddonConfig.QUALITY_4K_SOUND) {
            AddonConfig.QUALITY_4K_SOUND
        } else {
            AddonConfig.QUALITY_1080P
        }
        prefs(context).edit().putString("stream_quality", value).apply()
    }

    fun resultMode(context: Context): String =
        AddonConfig.normalizeResultMode(
            prefs(context).getString("result_mode", AddonConfig.RESULT_FAST),
        )

    fun setResultMode(context: Context, mode: String) {
        prefs(context).edit()
            .putString("result_mode", AddonConfig.normalizeResultMode(mode))
            .apply()
    }

    fun debridServices(context: Context): List<String> {
        val raw = prefs(context).getString("debrid_services", null) ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    fun setDebridServices(context: Context, services: List<String>) {
        prefs(context).edit()
            .putString("debrid_services", services.joinToString(","))
            .apply()
    }

    fun usbRootPath(context: Context): String? =
        prefs(context).getString("usb_root_path", null)

    fun setUsbRootPath(context: Context, path: String) {
        prefs(context).edit().putString("usb_root_path", path).apply()
    }

    fun configStatus(context: Context): String? =
        prefs(context).getString("config_status", null)

    fun setConfigStatus(context: Context, status: String?) {
        prefs(context).edit().putString("config_status", status).apply()
    }

    fun refreshDetectedLanIp(context: Context): LanAddress? {
        val best = LanIpDetector.detectBest(context) ?: return null
        prefs(context).edit()
            .putString("detected_lan_host", best.address)
            .putString("detected_interface", best.interfaceName)
            .putString("detected_type", best.type)
            .apply()
        return best
    }

    fun detectedLanHost(context: Context): String? =
        prefs(context).getString("detected_lan_host", null)

    fun detectedInterface(context: Context): String? =
        prefs(context).getString("detected_interface", null)

    fun manualLanHost(context: Context): String? =
        prefs(context).getString("manual_lan_host", null)?.takeIf { it.isNotBlank() }

    fun setManualLanHost(context: Context, host: String?) {
        prefs(context).edit()
            .putString("manual_lan_host", host?.trim()?.ifBlank { null })
            .apply()
    }

    /** LAN IP for phone/PC configure and health URLs. */
    fun lanHost(context: Context): String {
        manualLanHost(context)?.let { return it }
        detectedLanHost(context)?.let { return it }
        refreshDetectedLanIp(context)?.let { return it.address }
        return "192.168.1.83"
    }

    /**
     * Public (WAN) IP for WuPlay install via config.wuplay.app.
     * Requires router port-forward: external :port → this TV's LAN IP :port.
     */
    fun publicHost(context: Context): String? =
        prefs(context).getString("public_host", null)?.takeIf { it.isNotBlank() }

    fun setPublicHost(context: Context, host: String?) {
        prefs(context).edit()
            .putString("public_host", host?.trim()?.ifBlank { null })
            .apply()
    }

    /** Host WuPlay should use — public IP preferred, else LAN. */
    fun wuplayHost(context: Context): String = publicHost(context) ?: lanHost(context)

    fun wuplayInstallUrl(context: Context): String =
        "http://${wuplayHost(context)}:${serverPort(context)}/manifest.json"

    fun publicInstallUrl(context: Context): String? {
        val host = publicHost(context) ?: return null
        return "http://$host:${serverPort(context)}/manifest.json"
    }

    /** Stremio: loopback. WuPlay: public/LAN URL for config.wuplay.app. */
    fun stremioInstallUrl(context: Context): String =
        if (BuildConfig.WUPLAY_MODE) {
            wuplayInstallUrl(context)
        } else {
            "http://127.0.0.1:${serverPort(context)}/manifest.json"
        }

    /** Player / addon install URL for the active client. */
    fun clientInstallUrl(context: Context): String = stremioInstallUrl(context)

    fun settingsUrl(context: Context): String =
        "http://${lanHost(context)}:${serverPort(context)}/settings"

    fun healthUrl(context: Context): String =
        "http://${lanHost(context)}:${serverPort(context)}/health"

    fun publicHealthUrl(context: Context): String? {
        val host = publicHost(context) ?: return null
        return "http://$host:${serverPort(context)}/health"
    }

    fun allDetected(context: Context): List<LanAddress> = LanIpDetector.detectAll(context)

    fun cacheDirPath(context: Context): String? =
        prefs(context).getString("cache_dir_path", null)

    fun setCacheDirPath(context: Context, path: String) {
        prefs(context).edit().putString("cache_dir_path", path).apply()
    }

    fun usbLabel(context: Context): String? =
        prefs(context).getString("usb_label", null)

    fun storageMode(context: Context): String =
        prefs(context).getString("storage_mode", null)
            ?: if (cacheDirPath(context) != null) "usb" else ""

    fun setUsbSelection(context: Context, cacheDirPath: String, label: String, usbRootPath: String) {
        prefs(context).edit()
            .putString("storage_mode", "usb")
            .putString("cache_dir_path", cacheDirPath)
            .putString("usb_label", label)
            .putString("usb_root_path", usbRootPath)
            .apply()
    }

    fun setInternalSelection(context: Context, cacheDirPath: String, cacheMaxGb: Int) {
        prefs(context).edit()
            .putString("storage_mode", "internal")
            .putString("cache_dir_path", cacheDirPath)
            .putString("usb_label", "Internal storage")
            .remove("usb_root_path")
            .putInt("cache_max_gb", cacheMaxGb.coerceIn(1, 4096))
            .apply()
    }

    fun clearStorageSelection(context: Context) {
        prefs(context).edit()
            .remove("storage_mode")
            .remove("cache_dir_path")
            .remove("usb_label")
            .remove("usb_root_path")
            .apply()
    }
}
