package app.localcache.stream

import android.content.Context
import android.util.Log
import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem
import app.localcache.model.Upstream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class UpstreamFetcher(private val context: Context) {

    fun loadUpstreams(): List<Upstream> = AddonConfig.load(context).upstreams()

    fun hasConfiguredUpstreams(): Boolean = AddonConfig.load(context).hasAnyUpstream()

    fun fetchAll(type: String, id: String): List<StreamItem> {
        val cacheKey = "$type/$id"
        cached(cacheKey)?.let {
            Log.i(TAG, "fetchAll $cacheKey -> ${it.size} streams (cached)")
            return it
        }

        val cfg = AddonConfig.load(context)
        val upstreams = cfg.upstreams()
        if (upstreams.isEmpty()) {
            Log.w(TAG, "fetchAll $cacheKey — no upstream manifests configured")
            return emptyList()
        }

        val timeoutS = cfg.upstreamTimeoutSeconds()
        val client = clientFor(timeoutS)
        val enabled = cfg.debridServices
        val startedAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "fetchAll $cacheKey from ${upstreams.size} upstreams " +
                "(mode=${cfg.resultMode}, timeout=${timeoutS}s)",
        )

        val results = runBlocking(Dispatchers.IO) {
            if (cfg.isCompleteResults()) {
                fetchComplete(client, upstreams, type, id, enabled)
            } else {
                fetchFast(client, upstreams, type, id, enabled, timeoutS * 1000L)
            }
        }

        Log.i(
            TAG,
            "fetchAll $cacheKey -> ${results.size} streams in ${elapsed(startedAt)} (mode=${cfg.resultMode})",
        )
        if (results.isNotEmpty()) store(cacheKey, results)
        return results
    }

    private suspend fun fetchComplete(
        client: OkHttpClient,
        upstreams: List<Upstream>,
        type: String,
        id: String,
        enabled: List<String>,
    ): List<StreamItem> = coroutineScope {
        val calls = ConcurrentHashMap<String, Call>()
        upstreams.map { upstream ->
            async { fetchOne(client, upstream, type, id, enabled, calls) }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    /**
     * Fast mode (hard cap still [AddonConfig.Snapshot.upstreamTimeoutSeconds] — 8s):
     * wait until **all instances of at least one provider family** have answered
     * (e.g. both Torrentio manifests) **and** there is a debrid-cached hit, then a short
     * grace for the rest. Complete mode still waits for everything.
     */
    private suspend fun fetchFast(
        client: OkHttpClient,
        upstreams: List<Upstream>,
        type: String,
        id: String,
        enabled: List<String>,
        hardTimeoutMs: Long,
    ): List<StreamItem> = coroutineScope {
        val calls = ConcurrentHashMap<String, Call>()
        val results = Channel<Pair<String, List<StreamItem>>>(Channel.UNLIMITED)
        val familySizes = upstreams.groupingBy { providerFamily(it.name) }.eachCount()

        val jobs = upstreams.map { upstream ->
            launch {
                results.send(upstream.name to fetchOne(client, upstream, type, id, enabled, calls))
            }
        }

        val collected = mutableListOf<StreamItem>()
        val replied = mutableSetOf<String>()
        var remaining = upstreams.size
        /** Set only once a full provider family is in + cached hit — then short grace applies. */
        var graceStartedAt: Long? = null
        val deadline = System.currentTimeMillis() + hardTimeoutMs

        while (remaining > 0) {
            val now = System.currentTimeMillis()
            val graceDeadline = graceStartedAt?.plus(GRACE_AFTER_FIRST_MS)
            val waitMs = when {
                graceDeadline != null -> minOf(graceDeadline, deadline) - now
                else -> deadline - now
            }
            if (waitMs <= 0L) break

            val (upstreamName, batch) = withTimeoutOrNull(waitMs) { results.receive() } ?: break
            remaining--
            replied.add(upstreamName)
            collected += batch

            val cachedSoFar = collected.count { DebridRules.isDebridCached(it, enabled) }
            val completeFamily = firstCompleteFamily(replied, familySizes)

            if (graceStartedAt == null && cachedSoFar > 0 && completeFamily != null) {
                graceStartedAt = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "fast: family \"$completeFamily\" complete " +
                        "(${familySizes[completeFamily]} instance(s)) with $cachedSoFar cached — " +
                        "grace ${GRACE_AFTER_FIRST_MS}ms for the rest",
                )
            } else if (graceStartedAt == null && remaining > 0) {
                val waitingFamilies = familySizes.keys.filter { family ->
                    replied.count { providerFamily(it) == family } < (familySizes[family] ?: 0)
                }
                Log.i(
                    TAG,
                    "fast: ${upstreamName} replied (${batch.size} streams, $cachedSoFar cached total); " +
                        "waiting for full provider family " +
                        "(pending: ${waitingFamilies.joinToString(", ").ifBlank { "none" }})",
                )
            }
        }

        jobs.forEach { it.cancel() }
        calls.values.forEach { call -> runCatching { call.cancel() } }
        results.close()

        collected.distinctBy { it.url }
    }

    /** "Torrentio 2" → "Torrentio"; "Comet Local" stays "Comet Local". */
    private fun providerFamily(name: String): String =
        name.replace(Regex("""\s+\d+$"""), "")

    /** First provider family where every configured instance has replied. */
    private fun firstCompleteFamily(
        replied: Set<String>,
        familySizes: Map<String, Int>,
    ): String? = familySizes.keys.firstOrNull { family ->
        replied.count { providerFamily(it) == family } >= (familySizes[family] ?: 0)
    }

    private fun fetchOne(
        client: OkHttpClient,
        upstream: Upstream,
        type: String,
        id: String,
        enabled: List<String>,
        calls: ConcurrentHashMap<String, Call>,
    ): List<StreamItem> {
        val url = "${upstream.baseUrl}/stream/${encode(type)}/${encode(id)}.json"
        val startedAt = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LocalCache/0.4.16")
                .build()
            val call = client.newCall(request)
            calls[upstream.name] = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "${upstream.name} HTTP ${response.code} after ${elapsed(startedAt)}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val items = parseStreams(upstream.name, type, id, JSONObject(body))
                val cachedCount = items.count { DebridRules.isDebridCached(it, enabled) }
                Log.i(
                    TAG,
                    "${upstream.name} -> ${items.size} streams ($cachedCount cached for enabled services) in ${elapsed(startedAt)}",
                )
                items
            }
        } catch (e: Exception) {
            Log.w(TAG, "${upstream.name} failed after ${elapsed(startedAt)}: ${e.message}")
            emptyList()
        } finally {
            calls.remove(upstream.name)
        }
    }

    private fun elapsed(startedAt: Long) = "${(System.currentTimeMillis() - startedAt) / 1000.0}s"

    private fun parseStreams(source: String, type: String, id: String, data: JSONObject): List<StreamItem> {
        val streams = data.optJSONArray("streams") ?: return emptyList()
        val out = mutableListOf<StreamItem>()

        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val playUrl = s.optString("url", "")
            if (!playUrl.startsWith("http")) continue

            val rawName = s.optString("name", s.optString("title", "Stream"))
            val title = s.optString("title").takeIf { it.isNotBlank() }
            val description = s.optString("description").takeIf { it.isNotBlank() }
            val filename = s.optJSONObject("behaviorHints")
                ?.optString("filename")
                ?.takeIf { it.isNotBlank() }
            val cacheKey = buildCacheKey(type, id, source, playUrl)

            val draft = StreamItem(
                cacheKey = cacheKey,
                source = source,
                label = rawName.replace("\n", " · ").replace(Regex("\\s+"), " ").trim(),
                rawName = rawName,
                url = playUrl,
                title = title,
                description = description,
                filename = filename,
            )
            // Prefer the real release / file name for APK status + USB labels.
            val release = StreamLabelFormatter.releaseName(draft)
            out.add(draft.copy(label = release))
        }
        return out
    }

    private fun buildCacheKey(type: String, id: String, source: String, url: String): String {
        val hash = sha1(url).take(12)
        val safeId = id.replace(":", "-")
        return "${type}_${safeId}_${source.replace(" ", "-")}_$hash"
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun cached(key: String): List<StreamItem>? {
        val hit = resultCache[key] ?: return null
        if (System.currentTimeMillis() - hit.storedAt > CACHE_TTL_MS) {
            resultCache.remove(key)
            return null
        }
        return hit.streams
    }

    private fun store(key: String, streams: List<StreamItem>) {
        resultCache[key] = CachedResult(streams, System.currentTimeMillis())
    }

    fun clearCache() {
        resultCache.clear()
    }

    private data class CachedResult(val streams: List<StreamItem>, val storedAt: Long)

    companion object {
        private const val TAG = "UpstreamFetcher"
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
        private const val GRACE_AFTER_FIRST_MS = 900L
        private val resultCache = ConcurrentHashMap<String, CachedResult>()
        private val clients = ConcurrentHashMap<Long, OkHttpClient>()

        private fun clientFor(timeoutS: Long): OkHttpClient =
            clients.getOrPut(timeoutS) {
                OkHttpClient.Builder()
                    .connectTimeout(minOf(5L, timeoutS), TimeUnit.SECONDS)
                    .readTimeout(timeoutS, TimeUnit.SECONDS)
                    .callTimeout(timeoutS, TimeUnit.SECONDS)
                    .build()
            }
    }
}
