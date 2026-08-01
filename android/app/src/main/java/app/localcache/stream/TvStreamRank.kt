package app.localcache.stream

import app.localcache.config.AddonConfig
import app.localcache.model.StreamItem

object TvStreamRank {
    private fun streamText(stream: StreamItem): String =
        listOfNotNull(stream.rawName, stream.title, stream.description, stream.source, stream.label)
            .joinToString(" ")
            .lowercase()

    private fun has(text: String, pattern: String): Boolean =
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)

    /**
     * Weighted for an eARC setup. "Atmos" alone is ambiguous — codec decides the tier.
     */
    private fun audioScore(text: String, favorLossless: Boolean): Int {
        val atmos = has(text, """\batmos\b""")
        val lossless = has(text, """\btruehd\b|\bdts[- ]?hd[- ]?ma\b|\bdts[- ]?x\b|\bflac\b|\bpcm\b""")

        var score = when {
            lossless && atmos -> if (favorLossless) 450 else 200
            lossless -> if (favorLossless) 350 else 160
            atmos -> 250
            has(text, """\bdts[- ]?hd\b""") -> 200
            has(text, """\bdts\b|\bac3\b|\bdd5\.1\b""") -> 100
            has(text, """\beac3\b|\bdd\+|\bddplus\b|\bddp\b""") -> 90
            else -> 0
        }

        if (has(text, """\b7\.1\b""")) score += 60
        else if (has(text, """\b5\.1\b""")) score += 40

        if (has(text, """\b2\.0\b|\bstereo\b""") && !has(text, """\b(5\.1|7\.1)\b""")) score -= 120

        return score
    }

    fun score(
        stream: StreamItem,
        enabledDebrid: List<String>,
        quality: String,
    ): Int {
        val text = streamText(stream)
        var score = 0
        val favorLossless = quality == AddonConfig.QUALITY_4K_SOUND

        when {
            has(text, """\b(4320p?|7680|8k)\b""") -> score -= 10_000
            has(text, """\b(2160p?|4k|uhd)\b""") ->
                score += if (favorLossless) 700 else -500
            has(text, """\b1080p?\b|\bfullhd\b|\b1080\b""") -> score += 500
            has(text, """\b720p?\b""") -> score += 200
            has(text, """\b480p?\b""") -> score += 80
            else -> score += 40
        }

        score += audioScore(text, favorLossless)

        if (has(text, """\bhdr10\b|\bhdr\b""")) score += 50
        if (has(text, """\b10bit\b|\b10-bit\b""")) score += 30

        // Old WuPlay weights: cached boost + AllDebrid starts faster.
        if (DebridRules.isDebridCached(stream, enabledDebrid)) score += 80
        if (DebridRules.isAllDebrid(stream)) score += 150

        val maxGb = DebridRules.maxSizeGb(quality)
        val strictGb = DebridRules.strictSizeGb(quality)
        val gb = DebridRules.parseSizeGb(stream)
        if (gb != null) {
            if (gb <= strictGb) score += 30
            if (gb <= maxGb) score += 15
            score += minOf((gb * 2).toInt(), if (favorLossless) 60 else 40)
        }

        return score
    }

    fun rank(
        streams: List<StreamItem>,
        enabledDebrid: List<String> = AddonConfig.ALL_DEBRID_SERVICES,
        quality: String = AddonConfig.QUALITY_1080P,
    ): List<StreamItem> =
        streams
            .map { it.copy(qualityScore = score(it, enabledDebrid, quality)) }
            .sortedWith(
                compareByDescending<StreamItem> { it.qualityScore }
                    .thenByDescending { if (DebridRules.isAllDebrid(it)) 1 else 0 },
            )
}
