package app.localcache.stream

import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem
import app.localcache.model.StreamPick

object TvStreamOrder {
    /**
     * Curated order:
     * #1 best quality (cached if possible), #2 safe 1080p,
     * #3 best cached any debrid, #4 best cached on a *different* debrid,
     * #5 one download fallback, then more cached options only.
     *
     * Quality modes still apply to the TV pool (1080p hides 4K; 4k_sound prefers it).
     */
    fun buildOrdered(
        allStreams: List<StreamItem>,
        onDrive: List<StreamPick> = emptyList(),
        quality: String = AddonConfig.QUALITY_1080P,
        enabledDebrid: List<String> = AddonConfig.ALL_DEBRID_SERVICES,
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

        // #1 — best quality within budget, cached if at all possible (old behaviour).
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

        // #4 — best cached on a *different* debrid than #3 (e.g. AD then TB).
        add(
            pcCached.firstOrNull { stream ->
                if (seen.contains(stream.cacheKey)) return@firstOrNull false
                val service = DebridRules.matchedService(stream, enabledDebrid) ?: return@firstOrNull false
                slot3Service != null && service != slot3Service
            },
            "best_other",
        )

        // #5 — one download fallback (may be uncached).
        add(pcRanked.firstOrNull { !seen.contains(it.cacheKey) }, "download")

        // Extra rows: more *cached* quality picks only — do not flood with uncached junk.
        val limit = maxStreams.coerceAtLeast(5)
        for (stream in tvRanked) {
            if (picks.size >= limit) break
            if (!DebridRules.isDebridCached(stream, enabledDebrid)) continue
            if (!seen.contains(stream.cacheKey)) add(stream, null)
        }
        for (stream in pcCached) {
            if (picks.size >= limit) break
            if (!seen.contains(stream.cacheKey)) add(stream, null)
        }

        return StreamBuildResult(picks, rawCount, strict.size, usedFallback)
    }
}
