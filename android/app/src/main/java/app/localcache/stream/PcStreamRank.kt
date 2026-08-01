package app.localcache.stream

import app.localcache.model.StreamItem

/**
 * Faithful port of the PC add-on's `src/stream-rank.js` scoring, so the picks the TV shows
 * in slots 3-5 are the same three the PC shows in slots 1-3.
 *
 * Unlike [TvStreamRank] this does NOT penalise 4K — the PC prefers it.
 */
object PcStreamRank {

    private fun streamText(stream: StreamItem): String =
        listOfNotNull(stream.rawName, stream.title, stream.description, stream.source, stream.label)
            .joinToString(" ")
            .lowercase()

    private fun has(text: String, pattern: String): Boolean =
        Regex(pattern).containsMatchIn(text)

    private fun debridPriority(stream: StreamItem): Int {
        if (DebridRules.isAllDebrid(stream)) return 2
        if (DebridRules.isTorBox(stream)) return 1
        return 0
    }

    fun score(stream: StreamItem): Int {
        val text = streamText(stream)
        var score = 0

        when {
            has(text, """\b(4320p?|7680|8k)\b""") -> score += 1000
            has(text, """\b(2160p?|4k|uhd)\b""") -> score += 800
            has(text, """\b1080p?\b|\bfullhd\b|\b1080\b""") -> score += 500
            has(text, """\b720p?\b""") -> score += 300
            has(text, """\b480p?\b""") -> score += 100
            has(text, """\b360p?\b""") -> score += 50
        }

        if (has(text, """\bdolby vision\b|\bdovi\b|\bdv\b""")) score += 220
        if (has(text, """\bhdr10\+|hdr10plus\b""")) score += 170
        if (has(text, """\bhdr10\b""")) score += 150
        else if (has(text, """\bhdr\b""")) score += 120
        if (has(text, """\bhlg\b""")) score += 80
        if (has(text, """\b10bit\b|\b10-bit\b""")) score += 40

        if (has(text, """\bdolby atmos\b|\batmos\b""")) score += 200
        if (has(text, """\bdts[- ]?x\b""")) score += 170
        if (has(text, """\bdts[- ]?hd[- ]?ma\b""")) score += 150
        if (has(text, """\btruehd\b""")) score += 140
        if (has(text, """\bdts[- ]?hd\b""")) score += 100
        if (has(text, """\beac3\b|\bdd\+|\bddplus\b""")) score += 60
        if (has(text, """\b7\.1\b""")) score += 50
        if (has(text, """\b5\.1\b""")) score += 30

        // AllDebrid starts noticeably faster than TorBox, so it outweighs it here.
        if (DebridRules.isAllDebrid(stream)) score += 120
        if (DebridRules.isTorBox(stream)) score += 40
        if (has(text, """\brd[+⚡]""")) score += 40

        val gb = Regex("""(\d+(?:\.\d+)?)\s*gb\b""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        if (gb != null) {
            score += minOf(gb * 3, 120.0).toInt()
        } else {
            val mb = Regex("""(\d+(?:\.\d+)?)\s*mb\b""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            if (mb != null) score += minOf(mb / 50, 40.0).toInt()
        }

        if (has(text, """\b(hdcam|camrip|cam\b|telesync|\bts\b|telecine)\b""")) score -= 600
        if (has(text, """\b(scr\b|screener|dvdscr|\br5\b)\b""")) score -= 450
        if (has(text, """\b(workprint|\bwp\b)\b""")) score -= 350

        return score
    }

    fun rank(streams: List<StreamItem>): List<StreamItem> =
        streams
            .map { it.copy(qualityScore = score(it)) }
            .sortedWith(
                compareByDescending<StreamItem> { it.qualityScore }
                    .thenByDescending { debridPriority(it) }
            )
}
