package app.localcache.storage

import android.content.Context
import android.util.Log
import app.localcache.Prefs
import app.localcache.config.AddonConfig
import app.localcache.model.StreamPick
import app.localcache.stream.TvStreamOrder
import app.localcache.stream.UpstreamFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * After a series episode the user started finishes downloading, optionally queue the
 * *next* episode only (one ahead). Prefetched episodes do not chain further.
 * Off by default ([Prefs.autoNextEpisode]).
 */
object AutoNextEpisode {
    private const val TAG = "AutoNextEpisode"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun onDownloadComplete(context: Context, entry: CacheEntry) {
        if (!Prefs.autoNextEpisode(context)) return
        // Only one step ahead: never chain from an episode we auto-started ourselves.
        if (entry.autoPrefetched) {
            Log.i(TAG, "skip chain — ${entry.id} was auto-prefetched")
            return
        }
        if (!entry.type.equals("series", ignoreCase = true)) return
        val id = entry.id?.trim().orEmpty()
        if (id.isBlank() || ':' !in id) return

        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var nextId = resolveNextEpisodeId(id) ?: run {
                    Log.i(TAG, "no next episode after $id")
                    return@launch
                }
                Log.i(TAG, "auto-next (one ahead): $id → $nextId")
                var key = prepareDownload(appCtx, nextId)
                if (key == null) {
                    val parsed = parseEpisodeId(id) ?: return@launch
                    val rollover = "${parsed.first}:${parsed.second + 1}:1"
                    if (rollover != nextId) {
                        Log.i(TAG, "auto-next: trying season rollover $rollover")
                        key = prepareDownload(appCtx, rollover)
                    }
                }
                if (key != null) {
                    CacheRegistry.get(key)?.autoPrefetched = true
                    DownloadEngine.queueOrStart(appCtx, key)
                }
            } catch (e: Exception) {
                Log.w(TAG, "auto-next failed after ${entry.id}: ${e.message}")
            }
        }
    }

    /**
     * Prefer Cinemeta’s episode list; fall back to season/episode +1.
     */
    fun resolveNextEpisodeId(currentId: String): String? {
        val parsed = parseEpisodeId(currentId) ?: return null
        val (imdb, season, episode) = parsed

        val fromMeta = nextFromCinemeta(imdb, currentId, season, episode)
        if (fromMeta != null) return fromMeta

        return "$imdb:$season:${episode + 1}"
    }

    private fun nextFromCinemeta(
        imdb: String,
        currentId: String,
        season: Int,
        episode: Int,
    ): String? {
        val url = "https://v3-cinemeta.strem.io/meta/series/$imdb.json"
        val body = runCatching {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
        }.getOrNull() ?: return null

        val meta = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: return null
        val videos = meta.optJSONArray("videos") ?: return null

        data class Ep(val id: String, val season: Int, val episode: Int)

        val list = buildList {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vid = v.optString("id").trim()
                val s = v.optInt("season", -1)
                val e = v.optInt("episode", -1)
                if (vid.isBlank() || s < 0 || e < 0) continue
                // Skip specials / season 0 unless the user was already on one.
                if (s == 0 && season > 0) continue
                add(Ep(vid, s, e))
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))

        if (list.isEmpty()) return null

        val idx = list.indexOfFirst { ep ->
            ep.id.equals(currentId, ignoreCase = true) ||
                (ep.season == season && ep.episode == episode)
        }
        if (idx >= 0) return list.getOrNull(idx + 1)?.id

        // Current id missing from meta — first episode after (season, episode).
        return list.firstOrNull { it.season > season || (it.season == season && it.episode > episode) }?.id
    }

    private fun prepareDownload(context: Context, nextId: String): String? {
        val cfg = AddonConfig.load(context)
        if (!cfg.hasAnyUpstream()) {
            Log.w(TAG, "auto-next skipped — no upstream manifests")
            return null
        }

        val onDrive = LocalLibrary.forTitle(context, "series", nextId)
        if (onDrive.any { it.complete }) {
            Log.i(TAG, "auto-next skipped — $nextId already on storage")
            return null
        }

        onDrive.firstOrNull { !it.complete && it.cacheKey.isNotBlank() && it.url.isNotBlank() }?.let { partial ->
            CacheRegistry.registerLocal(partial)
            CacheRegistry.attachCachePath(context, partial.cacheKey)
            Log.i(TAG, "auto-next resume partial ${partial.cacheKey}")
            return partial.cacheKey
        }

        val streams = UpstreamFetcher(context).fetchAll("series", nextId)
        if (streams.isEmpty()) {
            // Last episode of a season: try next season ep 1 once if bump-style id was used.
            Log.i(TAG, "auto-next: no streams for $nextId")
            return null
        }

        val maxFit = if (StorageMode.isInternal(context)) {
            StorageMode.roomBytesForNewFile(context)
        } else {
            0L
        }
        val built = TvStreamOrder.buildOrdered(
            allStreams = streams,
            onDrive = emptyList(),
            quality = cfg.streamQuality,
            enabledDebrid = cfg.debridServices,
            completeResults = false,
            maxFitBytes = maxFit,
        )
        val pick = built.picks.firstOrNull { usablePick(it) } ?: return null

        CacheRegistry.register(pick.stream, "series", nextId)
        CacheRegistry.attachCachePath(context, pick.stream.cacheKey)
        Log.i(TAG, "auto-next queued ${pick.stream.cacheKey} (${pick.slot})")
        return pick.stream.cacheKey
    }

    private fun usablePick(pick: StreamPick): Boolean {
        if (pick.slot == "fits_none") return false
        val url = pick.stream.url
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun parseEpisodeId(id: String): Triple<String, Int, Int>? {
        val parts = id.trim().split(":")
        if (parts.size < 3) return null
        val imdb = parts[0]
        if (!imdb.startsWith("tt", ignoreCase = true)) return null
        val season = parts[1].toIntOrNull() ?: return null
        val episode = parts[2].toIntOrNull() ?: return null
        return Triple(imdb, season, episode)
    }
}
