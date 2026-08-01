package app.localcache.stream

import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem
import app.localcache.model.StreamPick

object TvStreamOrder {
    /**
     * Curated order:
     * #1 best quality (cached if possible), #2 safe 1080p,
     * #3 best cached any debrid, #4 best cached on a *different* debrid,
     * #5–#6 best 720p (cached first, smaller first) — slow-link safety valve,
     * then 3 more cached, then 5 uncached (for users without debrid).
     */
    fun buildOrdered(
        allStreams: List<StreamItem>,
        onDrive: List<StreamPick> = emptyList(),
        quality: String = AddonConfig.QUALITY_1080P,
        enabledDebrid: List<String> = AddonConfig.ALL_DEBRID_SERVICES,
        @Suppress("UNUSED_PARAMETER")
        maxStreams: Int = 25,
    ): StreamBuildResult {
        val rawCount = allStreams.size
        val playable = allStreams.filter { !DebridRules.isExcluded(it) }
        val strict = allStreams.filter { DebridRules.passesTvFilters(it, quality) }
        val usedFallback = strict.isEmpty() && playable.isNotEmpty()

        val tvPool = if (strict.isNotEmpty()) {
            strict
        } else {
            playable
                .filter { !DebridRules.is8K(it) }
                .filter { quality == AddonConfig.QUALITY_4K_SOUND || !DebridRules.is4K(it) }
                .sortedBy { DebridRules.parseSizeGb(it) ?: 999.0 }
        }

        val picks = mutableListOf<StreamPick>()
        val seen = mutableSetOf<String>()

        fun add(stream: StreamItem?, slot: String?) {
            if (stream == null || !seen.add(stream.cacheKey)) return
            picks.add(StreamPick(stream, slot))
        }

        onDrive.forEach { add(it.stream, it.slot) }

        if (playable.isEmpty()) {
            return StreamBuildResult(picks, rawCount, strict.size, usedFallback)
        }

        val tvRanked = TvStreamRank.rank(tvPool, enabledDebrid, quality)
        val pcRanked = PcStreamRank.rank(playable)
        val pcCached = pcRanked.filter { DebridRules.isDebridCached(it, enabledDebrid) }
        val cached = tvRanked.filter { DebridRules.isDebridCached(it, enabledDebrid) }
        val usingUncachedFallback = cached.isEmpty() && tvRanked.isNotEmpty()

        // #1 — best quality within budget, cached if at all possible.
        add(
            cached.firstOrNull() ?: tvRanked.firstOrNull(),
            when {
                usedFallback -> "fallback"
                usingUncachedFallback -> "primary_uncached"
                else -> "primary"
            },
        )

        // #2 — safe 1080p backup (AllDebrid preferred — starts faster).
        val safe1080 = tvRanked
            .filter {
                DebridRules.is1080ish(it) &&
                    !DebridRules.is4K(it) &&
                    (DebridRules.parseSizeGb(it) ?: Double.MAX_VALUE) <= DebridRules.SAFE_SIZE_GB &&
                    !seen.contains(it.cacheKey)
            }
            .sortedWith(
                compareByDescending<StreamItem> { if (DebridRules.isDebridCached(it, enabledDebrid)) 1 else 0 }
                    .thenByDescending { if (DebridRules.isAllDebrid(it)) 1 else 0 }
                    .thenByDescending { it.qualityScore },
            )
        add(safe1080.firstOrNull(), "safe")

        if (picks.size < 2) {
            tvRanked.firstOrNull { !seen.contains(it.cacheKey) }
                ?.let { add(it, "backup") }
        }

        // #3 — best cached on any enabled debrid (PC ranking).
        val bestCachedAny = pcCached.firstOrNull { !seen.contains(it.cacheKey) }
        add(bestCachedAny, "best")
        val slot3Service = bestCachedAny?.let { DebridRules.matchedService(it, enabledDebrid) }

        // #4 — best cached on a *different* debrid than #3.
        add(
            pcCached.firstOrNull { stream ->
                if (seen.contains(stream.cacheKey)) return@firstOrNull false
                val service = DebridRules.matchedService(stream, enabledDebrid) ?: return@firstOrNull false
                slot3Service != null && service != slot3Service
            },
            "best_other",
        )

        // #5 / #6 — 720p safety valve (cached first, then smaller files).
        val p720 = playable
            .filter { DebridRules.is720Only(it) && !seen.contains(it.cacheKey) }
            .sortedWith(
                compareByDescending<StreamItem> { if (DebridRules.isDebridCached(it, enabledDebrid)) 1 else 0 }
                    .thenBy { DebridRules.parseSizeGb(it) ?: 999.0 }
                    .thenByDescending { it.qualityScore },
            )
        add(p720.getOrNull(0), "720a")
        add(p720.firstOrNull { !seen.contains(it.cacheKey) }, "720b")

        // Next 3 — more cached options.
        var cachedExtra = 0
        for (stream in tvRanked.asSequence() + pcCached.asSequence()) {
            if (cachedExtra >= 3) break
            if (seen.contains(stream.cacheKey)) continue
            if (!DebridRules.isDebridCached(stream, enabledDebrid)) continue
            add(stream, "cached_extra")
            cachedExtra++
        }

        // Next 5 — uncached (people without debrid / download later).
        var uncachedExtra = 0
        for (stream in pcRanked) {
            if (uncachedExtra >= 5) break
            if (seen.contains(stream.cacheKey)) continue
            if (DebridRules.isDebridCached(stream, enabledDebrid)) continue
            add(stream, "uncached")
            uncachedExtra++
        }

        return StreamBuildResult(picks, rawCount, strict.size, usedFallback)
    }
}
