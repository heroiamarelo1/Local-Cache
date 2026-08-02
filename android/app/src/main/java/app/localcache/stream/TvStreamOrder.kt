package app.localcache.stream

import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem
import app.localcache.model.StreamPick


object TvStreamOrder {
    /**
     * Curated order (no repeats):
     * on-drive rows first
     * (internal mode) best that fits device — 1080p/720p, cached preferred
     * then normal curated slots…
     *
     * Fast: stop after those curated rows.
     * Complete: then append everything else still unused.
     */
    fun buildOrdered(
        allStreams: List<StreamItem>,
        onDrive: List<StreamPick> = emptyList(),
        quality: String = AddonConfig.QUALITY_1080P,
        enabledDebrid: List<String> = AddonConfig.ALL_DEBRID_SERVICES,
        completeResults: Boolean = false,
        /** When > 0, insert a fits-device pick after on-drive (internal storage mode). */
        maxFitBytes: Long = 0L,
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

        if (maxFitBytes > 0L) {
            val fit = bestThatFits(playable, enabledDebrid, seen, maxFitBytes)
            if (fit != null) {
                add(fit, "fits_internal")
            } else {
                // Info-only row — StreamResponseBuilder omits url so it cannot play.
                picks.add(StreamPick(NO_FIT_PLACEHOLDER, "fits_none"))
            }
        }

        val tvRanked = TvStreamRank.rank(tvPool, enabledDebrid, quality)
        val pcRanked = PcStreamRank.rank(playable)
        val pcCached = pcRanked.filter { DebridRules.isDebridCached(it, enabledDebrid) }
        val tvCached = tvRanked.filter { DebridRules.isDebridCached(it, enabledDebrid) }
        val usingUncachedFallback = tvCached.isEmpty() && tvRanked.isNotEmpty()

        // Best for quality mode (4K+sound → best 4K; 1080p → best 1080p pool).
        val primary = tvCached.firstOrNull() ?: tvRanked.firstOrNull()
        add(
            primary,
            when {
                usedFallback -> "fallback"
                usingUncachedFallback -> "primary_uncached"
                else -> "primary"
            },
        )
        val primaryService = primary?.let { DebridRules.matchedService(it, enabledDebrid) }

        // #2 — safe 1080p; prefer a different debrid than #1 so 1080p mode isn't a duplicate vibe.
        val safe1080 = playable
            .filter {
                DebridRules.is1080ish(it) &&
                    !DebridRules.is4K(it) &&
                    (DebridRules.parseSizeGb(it) ?: Double.MAX_VALUE) <= DebridRules.SAFE_SIZE_GB &&
                    !seen.contains(it.cacheKey)
            }
            .sortedWith(
                compareByDescending<StreamItem> { if (DebridRules.isDebridCached(it, enabledDebrid)) 1 else 0 }
                    .thenByDescending {
                        val svc = DebridRules.matchedService(it, enabledDebrid)
                        if (primaryService != null && svc != null && svc != primaryService) 1 else 0
                    }
                    .thenByDescending { if (DebridRules.isAllDebrid(it)) 1 else 0 }
                    .thenByDescending { it.qualityScore }
                    .thenBy { DebridRules.parseSizeGb(it) ?: 999.0 },
            )
        add(safe1080.firstOrNull(), "safe")

        if (picks.size < 2) {
            tvRanked.firstOrNull { !seen.contains(it.cacheKey) }
                ?.let { add(it, "backup") }
        }

        // #3 — best cached on any enabled debrid.
        val bestCachedAny = pcCached.firstOrNull { !seen.contains(it.cacheKey) }
        add(bestCachedAny, "best")
        val slot3Service = bestCachedAny?.let { DebridRules.matchedService(it, enabledDebrid) }

        // #4 — best cached on a different debrid than #3.
        add(
            pcCached.firstOrNull { stream ->
                if (seen.contains(stream.cacheKey)) return@firstOrNull false
                val service = DebridRules.matchedService(stream, enabledDebrid) ?: return@firstOrNull false
                slot3Service != null && service != slot3Service
            },
            "best_other",
        )

        // #5 / #6 — 720p (cached first, smaller first).
        val p720 = playable
            .filter { DebridRules.is720Only(it) && !seen.contains(it.cacheKey) }
            .sortedWith(
                compareByDescending<StreamItem> { if (DebridRules.isDebridCached(it, enabledDebrid)) 1 else 0 }
                    .thenBy { DebridRules.parseSizeGb(it) ?: 999.0 }
                    .thenByDescending { it.qualityScore },
            )
        add(p720.firstOrNull { !seen.contains(it.cacheKey) }, "720a")
        add(p720.firstOrNull { !seen.contains(it.cacheKey) }, "720b")

        // #7–#9 — three more cached: 1× 4K, 2× 1080p (from full playable pool).
        add(
            firstCached(pcRanked, enabledDebrid, seen) { DebridRules.is4K(it) },
            "cached_4k",
        )
        add(
            firstCached(pcRanked, enabledDebrid, seen) {
                DebridRules.is1080ish(it) && !DebridRules.is4K(it)
            },
            "cached_1080a",
        )
        add(
            firstCached(pcRanked, enabledDebrid, seen) {
                DebridRules.is1080ish(it) && !DebridRules.is4K(it)
            },
            "cached_1080b",
        )

        // #10–#14 — five uncached: 2× 4K, 2× 1080p, 1× 720p.
        add(
            firstUncached(pcRanked, enabledDebrid, seen) { DebridRules.is4K(it) },
            "uncached_4k_a",
        )
        add(
            firstUncached(pcRanked, enabledDebrid, seen) { DebridRules.is4K(it) },
            "uncached_4k_b",
        )
        add(
            firstUncached(pcRanked, enabledDebrid, seen) {
                DebridRules.is1080ish(it) && !DebridRules.is4K(it)
            },
            "uncached_1080a",
        )
        add(
            firstUncached(pcRanked, enabledDebrid, seen) {
                DebridRules.is1080ish(it) && !DebridRules.is4K(it)
            },
            "uncached_1080b",
        )
        add(
            firstUncached(pcRanked, enabledDebrid, seen) { DebridRules.is720Only(it) },
            "uncached_720",
        )

        // Complete: dump the rest. Fast: stop at the curated list.
        if (completeResults) {
            for (stream in pcRanked) {
                if (!seen.contains(stream.cacheKey)) add(stream, null)
            }
        }

        return StreamBuildResult(picks, rawCount, strict.size, usedFallback)
    }

    private val NO_FIT_PLACEHOLDER = StreamItem(
        cacheKey = "__local_cache_no_fit__",
        source = "Local Cache",
        label = "no fit",
        rawName = "no fit",
        url = "",
    )

    private fun bestThatFits(
        playable: List<StreamItem>,
        enabledDebrid: List<String>,
        seen: Set<String>,
        maxFitBytes: Long,
    ): StreamItem? {
        val maxGb = maxFitBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return playable
            .filter { stream ->
                if (seen.contains(stream.cacheKey)) return@filter false
                if (DebridRules.is4K(stream) || DebridRules.is8K(stream)) return@filter false
                if (!DebridRules.is1080ish(stream) && !DebridRules.is720Only(stream)) return@filter false
                val sizeGb = DebridRules.parseSizeGb(stream) ?: return@filter false
                sizeGb <= maxGb
            }
            .sortedWith(
                compareByDescending<StreamItem> { if (DebridRules.isDebridCached(it, enabledDebrid)) 1 else 0 }
                    .thenByDescending { if (DebridRules.is1080ish(it)) 1 else 0 }
                    .thenByDescending { it.qualityScore }
                    .thenBy { DebridRules.parseSizeGb(it) ?: 999.0 },
            )
            .firstOrNull()
    }

    private fun firstCached(
        streams: List<StreamItem>,
        enabledDebrid: List<String>,
        seen: Set<String>,
        predicate: (StreamItem) -> Boolean,
    ): StreamItem? =
        streams
            .filter {
                !seen.contains(it.cacheKey) &&
                    DebridRules.isDebridCached(it, enabledDebrid) &&
                    predicate(it)
            }
            .sortedWith(
                compareByDescending<StreamItem> { it.qualityScore }
                    .thenByDescending { if (DebridRules.isAllDebrid(it)) 1 else 0 },
            )
            .firstOrNull()

    private fun firstUncached(
        streams: List<StreamItem>,
        enabledDebrid: List<String>,
        seen: Set<String>,
        predicate: (StreamItem) -> Boolean,
    ): StreamItem? =
        streams.firstOrNull {
            !seen.contains(it.cacheKey) &&
                !DebridRules.isDebridCached(it, enabledDebrid) &&
                predicate(it)
        }
}
