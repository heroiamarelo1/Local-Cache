package app.localcache.update

import android.util.Log
import app.localcache.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks GitHub Releases for a newer Local Cache APK.
 * Results are cached in memory to avoid hammering the API.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    const val REPO_RELEASES_URL = "https://github.com/heroiamarelo1/Local-Cache/releases"
    const val LATEST_API_URL =
        "https://api.github.com/repos/heroiamarelo1/Local-Cache/releases/latest"
    const val DONATE_URL = "https://www.paypal.com/paypalme/heroiamarelo/2"

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    data class Status(
        val currentVersion: String,
        val latestVersion: String?,
        val updateAvailable: Boolean,
        val releaseUrl: String?,
        val apkUrl: String?,
        val releaseName: String?,
        val checkedAt: Long,
        val error: String? = null,
    ) {
        fun bannerLine(): String? {
            if (!updateAvailable || latestVersion.isNullOrBlank()) return null
            return "Update available: v$latestVersion (you have v$currentVersion)"
        }

        fun detailLine(): String? {
            if (!updateAvailable) return null
            return buildString {
                append("Download: ")
                append(apkUrl ?: releaseUrl ?: REPO_RELEASES_URL)
            }
        }
    }

    private val cached = AtomicReference<Status?>(null)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun peek(): Status? = cached.get()

    fun currentVersion(): String = BuildConfig.VERSION_NAME.trim().removePrefix("v")

    /** Blocking network check — call off the main thread. */
    fun check(force: Boolean = false): Status {
        val now = System.currentTimeMillis()
        val hit = cached.get()
        if (!force && hit != null && now - hit.checkedAt < CACHE_TTL_MS && hit.error == null) {
            return hit
        }

        val current = currentVersion()
        return try {
            val request = Request.Builder()
                .url(LATEST_API_URL)
                .header("User-Agent", "LocalCache/$current")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return remember(
                        Status(
                            currentVersion = current,
                            latestVersion = hit?.latestVersion,
                            updateAvailable = hit?.updateAvailable == true,
                            releaseUrl = hit?.releaseUrl ?: REPO_RELEASES_URL,
                            apkUrl = hit?.apkUrl,
                            releaseName = hit?.releaseName,
                            checkedAt = now,
                            error = "GitHub HTTP ${response.code}",
                        ),
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").trim()
                val latest = normalizeVersion(tag)
                val htmlUrl = json.optString("html_url").takeIf { it.isNotBlank() }
                    ?: REPO_RELEASES_URL
                val name = json.optString("name").takeIf { it.isNotBlank() }
                val apkUrl = firstPublicApk(json)
                val newer = latest != null && compareVersions(latest, current) > 0
                remember(
                    Status(
                        currentVersion = current,
                        latestVersion = latest,
                        updateAvailable = newer,
                        releaseUrl = htmlUrl,
                        apkUrl = apkUrl,
                        releaseName = name,
                        checkedAt = now,
                    ),
                ).also {
                    Log.i(
                        TAG,
                        "check: current=$current latest=$latest update=${it.updateAvailable}",
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message}")
            remember(
                Status(
                    currentVersion = current,
                    latestVersion = hit?.latestVersion,
                    updateAvailable = hit?.updateAvailable == true,
                    releaseUrl = hit?.releaseUrl ?: REPO_RELEASES_URL,
                    apkUrl = hit?.apkUrl,
                    releaseName = hit?.releaseName,
                    checkedAt = now,
                    error = e.message ?: "update check failed",
                ),
            )
        }
    }

    private fun remember(status: Status): Status {
        cached.set(status)
        return status
    }

    private fun firstPublicApk(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("PERSONAL", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            if (url != null) return url
        }
        return null
    }

    fun normalizeVersion(raw: String?): String? {
        val v = raw?.trim()?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty()
        if (v.isEmpty()) return null
        // Keep digits/dots only for compare (strip suffixes like -beta).
        val core = v.substringBefore('-').substringBefore('+')
        return core.takeIf { it.matches(Regex("""\d+(\.\d+)*""")) }
    }

    /** Positive if [a] is newer than [b]. */
    fun compareVersions(a: String, b: String): Int {
        val pa = normalizeVersion(a)?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val pb = normalizeVersion(b)?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da.compareTo(db)
        }
        return 0
    }
}
