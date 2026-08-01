package app.localcache.stream

import android.content.Context
import app.localcache.Prefs
import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem
import org.json.JSONArray
import org.json.JSONObject

object StreamDiagnostics {
    private const val TEST_MOVIE = "tt0111161"

    fun run(context: Context, fetcher: UpstreamFetcher, port: Int): JSONObject {
        val lanHost = Prefs.lanHost(context)
        val cfg = AddonConfig.load(context)
        val all = fetcher.fetchAll("movie", TEST_MOVIE)
        val strict = all.filter { DebridRules.passesTvFilters(it, cfg.streamQuality) }
        val built = TvStreamOrder.buildOrdered(
            allStreams = all,
            quality = cfg.streamQuality,
            enabledDebrid = cfg.debridServices,
            maxStreams = cfg.maxStreamsForClient(),
        )

        return JSONObject()
            .put("ok", true)
            .put("lanHost", lanHost)
            .put("port", port)
            .put("stremioInstall", "http://127.0.0.1:$port/manifest.json")
            .put("settings", "http://$lanHost:$port/settings")
            .put("health", "http://$lanHost:$port/health")
            .put("upstreamsConfigured", cfg.hasAnyUpstream())
            .put("streamQuality", cfg.streamQuality)
            .put("resultMode", cfg.resultMode)
            .put("note", "Configure /settings first, then install in Stremio with 127.0.0.1. Use LAN IP for /settings and /health from your phone.")
            .put("testMovie", TEST_MOVIE)
            .put("rawUpstreamCount", all.size)
            .put("strictFilterCount", strict.size)
            .put("outputStreamCount", built.picks.size)
            .put("usedFallback", built.usedFallback)
            .put("streamTestUrl", "http://$lanHost:$port/stream/movie/$TEST_MOVIE.json")
            .put("sampleRaw", sampleNames(all, 5))
            .put("sampleStrict", sampleNames(strict, 5))
            .put("sampleOutput", sampleNames(built.picks.map { it.stream }, 5))
    }

    private fun sampleNames(streams: List<StreamItem>, limit: Int): JSONArray {
        val arr = JSONArray()
        streams.take(limit).forEach { arr.put(it.rawName) }
        return arr
    }
}
