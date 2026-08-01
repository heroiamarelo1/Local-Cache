package app.localcache.stream

import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem

object DebridRules {
    /** 4K high-quality-sound mode: remux-friendly size. */
    const val MAX_SIZE_GB_4K = 40.0
    const val STRICT_SIZE_GB_4K = 30.0

    /** 1080p recommended mode. */
    const val MAX_SIZE_GB_1080 = 25.0
    const val STRICT_SIZE_GB_1080 = 20.0

    /** Ceiling for the safe 1080p backup pick. */
    const val SAFE_SIZE_GB = 20.0

    /** @deprecated Prefer maxSizeGb(quality). Kept for call sites that ignore quality. */
    const val MAX_SIZE_GB = MAX_SIZE_GB_4K
    const val STRICT_SIZE_GB = STRICT_SIZE_GB_4K

    enum class DebridService(
        val configName: String,
        private val patterns: List<Regex>,
    ) {
        ALL_DEBRID(
            "AllDebrid",
            listOf(
                Regex("""alldebrid""", RegexOption.IGNORE_CASE),
                Regex("""\[ad[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\bad[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        TORBOX(
            "TorBox",
            listOf(
                Regex("""torbox""", RegexOption.IGNORE_CASE),
                Regex("""\[tb[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\btb[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        REAL_DEBRID(
            "RealDebrid",
            listOf(
                Regex("""real[- ]?debrid""", RegexOption.IGNORE_CASE),
                Regex("""\[rd[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\brd[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        PREMIUMIZE(
            "Premiumize",
            listOf(
                Regex("""premiumize""", RegexOption.IGNORE_CASE),
                Regex("""\[pm[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\bpm[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        DEBRID_LINK(
            "DebridLink",
            listOf(
                Regex("""debrid[- ]?link""", RegexOption.IGNORE_CASE),
                Regex("""\[dl[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\bdl[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        EASY_DEBRID(
            "EasyDebrid",
            listOf(
                Regex("""easydebrid""", RegexOption.IGNORE_CASE),
                Regex("""\[ed[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\bed[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        OFFCLOUD(
            "Offcloud",
            listOf(
                Regex("""offcloud""", RegexOption.IGNORE_CASE),
                Regex("""\[oc[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\boc[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        ),
        PUTIO(
            "Putio",
            listOf(
                Regex("""put\.?io""", RegexOption.IGNORE_CASE),
                Regex("""\[pu[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\[putio[+⚡]?\]""", RegexOption.IGNORE_CASE),
                Regex("""\bpu[+⚡]""", RegexOption.IGNORE_CASE),
            ),
        );

        fun matches(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
    }

    private fun text(stream: StreamItem): String =
        listOfNotNull(stream.rawName, stream.title, stream.description, stream.source, stream.label)
            .joinToString("\n")

    fun enabledServices(names: Collection<String>): List<DebridService> {
        if (names.isEmpty()) return DebridService.entries.toList()
        return DebridService.entries.filter { service ->
            names.any { it.equals(service.configName, ignoreCase = true) }
        }
    }

    fun maxSizeGb(quality: String): Double =
        if (quality == AddonConfig.QUALITY_4K_SOUND) MAX_SIZE_GB_4K else MAX_SIZE_GB_1080

    fun strictSizeGb(quality: String): Double =
        if (quality == AddonConfig.QUALITY_4K_SOUND) STRICT_SIZE_GB_4K else STRICT_SIZE_GB_1080

    fun isExcluded(stream: StreamItem): Boolean {
        val t = text(stream)
        val url = stream.url
        if (url.contains("/debrid-sync/", ignoreCase = true)) return true
        if (Regex("comet sync|🔄|\\[warn\\]", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        return false
    }

    private fun serviceForCacheCode(code: String): DebridService? =
        when (code.uppercase()) {
            "AD" -> DebridService.ALL_DEBRID
            "TB" -> DebridService.TORBOX
            "RD" -> DebridService.REAL_DEBRID
            "PM" -> DebridService.PREMIUMIZE
            "DL" -> DebridService.DEBRID_LINK
            "ED" -> DebridService.EASY_DEBRID
            "OC" -> DebridService.OFFCLOUD
            "PU", "PUTIO" -> DebridService.PUTIO
            "HM", "DA" -> null // legacy marks; treat as cached if any service enabled below
            else -> null
        }

    /**
     * Cached on one of the user's enabled debrid services.
     * Matches WuPlay-style `[AD+]` / `[TB⚡]` marks (and bare `AD+` / `TB⚡`).
     */
    fun isDebridCached(stream: StreamItem, enabled: Collection<String>): Boolean {
        val t = text(stream)
        if (stream.url.contains("/debrid-sync/", ignoreCase = true)) return false
        if (Regex("comet sync|🔄|\\[warn\\]|\\[download\\]|not cached|uncached", RegexOption.IGNORE_CASE)
                .containsMatchIn(t)
        ) {
            return false
        }

        val services = enabledServices(enabled)
        if (services.isEmpty()) return false

        // Bracket form used by Torrentio / Comet: [AD+], [TB⚡], …
        // (Do not require a trailing \b after +/⚡ — those are already non-word chars.)
        val bracket = Regex(
            """\[(AD|RD|TB|PM|DL|ED|OC|PU|PUTIO|HM|DA)[+⚡]\]""",
            RegexOption.IGNORE_CASE,
        )
        for (match in bracket.findAll(t)) {
            val code = match.groupValues[1]
            val service = serviceForCacheCode(code)
            if (service != null && services.contains(service)) return true
            // Legacy HM/DA marks: accept when that text also names an enabled service.
            if (service == null && services.any { it.matches(t) }) return true
        }

        // Bare AD+ / TB⚡ (no brackets). Avoid \b after +/⚡ — it never matches before a space.
        val bare = Regex(
            """(?<![A-Za-z0-9])(AD|RD|TB|PM|DL|ED|OC|PU|PUTIO|HM|DA)[+⚡](?![A-Za-z0-9+⚡])""",
            RegexOption.IGNORE_CASE,
        )
        for (match in bare.findAll(t)) {
            val service = serviceForCacheCode(match.groupValues[1])
            if (service != null && services.contains(service)) return true
            if (service == null && services.any { it.matches(t) }) return true
        }

        return services.any { service ->
            service == DebridService.PUTIO &&
                service.matches(t) &&
                Regex("""cached|instant""", RegexOption.IGNORE_CASE).containsMatchIn(t)
        }
    }

    /** Back-compat: all services enabled. */
    fun isDebridCached(stream: StreamItem): Boolean =
        isDebridCached(stream, AddonConfig.ALL_DEBRID_SERVICES)

    fun matchedService(stream: StreamItem, enabled: Collection<String>): DebridService? {
        val t = text(stream)
        return enabledServices(enabled).firstOrNull { it.matches(t) }
    }

    fun serviceDisplayName(stream: StreamItem, enabled: Collection<String>): String? =
        matchedService(stream, enabled)?.configName

    fun serviceShortCode(service: DebridService): String = when (service) {
        DebridService.ALL_DEBRID -> "AD"
        DebridService.TORBOX -> "TB"
        DebridService.REAL_DEBRID -> "RD"
        DebridService.PREMIUMIZE -> "PM"
        DebridService.DEBRID_LINK -> "DL"
        DebridService.EASY_DEBRID -> "ED"
        DebridService.OFFCLOUD -> "OC"
        DebridService.PUTIO -> "PU"
    }

    /**
     * Display-only cache badge for Stremio rows. Detection still uses [isDebridCached];
     * this only chooses `[AD⚡]` vs `[AD⬇️]` (and peers).
     */
    fun displayCacheMark(stream: StreamItem, enabled: Collection<String>): String? {
        val service = matchedService(stream, enabled) ?: return null
        val code = serviceShortCode(service)
        val mark = if (isDebridCached(stream, enabled)) "⚡" else "⬇️"
        return "[$code$mark]"
    }

    fun isAllDebrid(stream: StreamItem): Boolean = DebridService.ALL_DEBRID.matches(text(stream))

    fun isTorBox(stream: StreamItem): Boolean = DebridService.TORBOX.matches(text(stream))

    fun parseSizeGb(stream: StreamItem): Double? {
        val t = text(stream).lowercase()
        Regex("""(\d+(?:\.\d+)?)\s*gb""").find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
        Regex("""(\d+(?:\.\d+)?)\s*mb""").find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it / 1024.0 }
        return null
    }

    fun isOverSizeLimit(stream: StreamItem, maxGb: Double): Boolean {
        val gb = parseSizeGb(stream) ?: return false
        return gb > maxGb
    }

    fun is4K(stream: StreamItem): Boolean {
        val t = text(stream).lowercase()
        return Regex("""\b(2160p?|4k|uhd)\b""").containsMatchIn(t)
    }

    fun is8K(stream: StreamItem): Boolean {
        val t = text(stream).lowercase()
        return Regex("""\b(4320p?|7680|8k)\b""").containsMatchIn(t)
    }

    fun is1080p(stream: StreamItem): Boolean = is1080ish(stream)

    fun is1080ish(stream: StreamItem): Boolean {
        val t = text(stream).lowercase()
        return Regex("""\b(1080p?|1080i|fullhd|1080)\b""").containsMatchIn(t)
    }

    fun passesTvFilters(stream: StreamItem, quality: String): Boolean {
        if (isExcluded(stream)) return false
        if (is8K(stream)) return false
        if (quality != AddonConfig.QUALITY_4K_SOUND && is4K(stream)) return false
        if (isOverSizeLimit(stream, maxSizeGb(quality))) return false
        val t = text(stream).lowercase()
        if (Regex("""\b(hdcam|camrip|cam\b|telesync|telecine|screener|dvdscr)\b""").containsMatchIn(t)) {
            return false
        }
        return true
    }

    fun passesTvFilters(stream: StreamItem): Boolean =
        passesTvFilters(stream, AddonConfig.QUALITY_4K_SOUND)
}
