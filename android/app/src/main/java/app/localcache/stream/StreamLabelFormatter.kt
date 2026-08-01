package app.localcache.stream

import app.localcache.model.StreamItem

/** Builds Stremio `name` / `description` lines for Local Cache streams. */
object StreamLabelFormatter {

    fun resolutionToken(stream: StreamItem): String = when {
        DebridRules.is4K(stream) -> "4K"
        DebridRules.is1080ish(stream) -> "1080p"
        else -> {
            val text = streamText(stream)
            when {
                Regex("""\b720p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "720p"
                Regex("""\b480p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "480p"
                else -> "HD"
            }
        }
    }

    fun sizeLabel(stream: StreamItem): String? =
        DebridRules.parseSizeGb(stream)?.let { "%.1f GB".format(it) }

    fun videoAudioLine(stream: StreamItem): String? {
        val text = streamText(stream)
        val videoParts = mutableListOf<String>()
        when {
            DebridRules.is4K(stream) -> videoParts.add("2160p")
            DebridRules.is1080ish(stream) -> videoParts.add("1080p")
            Regex("""\b720p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> videoParts.add("720p")
            Regex("""\b480p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> videoParts.add("480p")
        }
        if (Regex("""\bhdr10\+|hdr10\b|\bdolby[- ]?vision\b|\bdv\b|\bhdr\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        ) {
            when {
                Regex("""\bdolby[- ]?vision\b|\bdv\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    videoParts.add("Dolby Vision")
                Regex("""\bhdr10\+""", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    videoParts.add("HDR10+")
                else -> videoParts.add("HDR")
            }
        }

        val audio = when {
            Regex("""\btruehd\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                Regex("""\batmos\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "TrueHD Atmos"
            Regex("""\bdts[- ]?x\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "DTS-X"
            Regex("""\bdts[- ]?hd[- ]?ma\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "DTS-HD MA"
            Regex("""\batmos\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "Dolby Atmos"
            Regex("""\bdts[- ]?hd\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "DTS-HD"
            Regex("""\beac3\b|\bdd\+|\bddplus\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "DD+"
            Regex("""\bdts\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "DTS"
            Regex("""\bac3\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "AC3"
            else -> null
        }?.let { base ->
            val channels = when {
                Regex("""\b7\.1\b""").containsMatchIn(text) -> "7.1"
                Regex("""\b5\.1\b""").containsMatchIn(text) -> "5.1"
                else -> null
            }
            listOfNotNull(base, channels).joinToString(" ")
        }

        val video = videoParts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        if (video == null && audio == null) return null
        return listOfNotNull(
            video?.let { "📹 $it" },
            audio?.let { "🔊 $it" },
        ).joinToString(" · ")
    }

    /**
     * Real torrent / file name — not the short addon line ("Torrentio 4K HDR").
     * Prefers behaviorHints.filename, then title/description lines that look like releases.
     */
    fun releaseName(stream: StreamItem): String {
        val lines = buildList {
            stream.filename?.let { add(it) }
            listOfNotNull(stream.title, stream.description, stream.rawName, stream.label).forEach { block ->
                block.split('\n', '\r').map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
            }
        }

        val best = lines.firstOrNull { looksLikeRelease(it) }
            ?: lines.firstOrNull { !looksLikeAddonBranding(it) && !looksLikeMetaLine(it) }
            ?: lines.firstOrNull()
            ?: "Stream"

        return cleanReleaseLine(best).take(140)
    }

    fun streamName(
        stream: StreamItem,
        progress: Int,
        status: String?,
        enabledDebrid: List<String>,
    ): String {
        val res = resolutionToken(stream)
        val cached = DebridRules.isDebridCached(stream, enabledDebrid)
        val st = status?.lowercase().orEmpty()

        return when {
            progress >= 100 || st == "complete" ->
                "✅ Local Cache $res · Ready"
            st == "downloading" && progress > 0 ->
                "⬇️ Local Cache $res · $progress%"
            st == "paused" ->
                "⬇️ Local Cache $res · $progress% paused — tap to play/resume"
            progress > 0 ->
                "⬇️ Local Cache $res · $progress% on USB"
            cached ->
                "⚡ Local Cache $res · Start download"
            else ->
                "🧲 Local Cache $res · Start download"
        }
    }

    fun streamDescription(
        stream: StreamItem,
        progress: Int,
        status: String?,
        enabledDebrid: List<String>,
    ): String {
        val mark = DebridRules.displayCacheMark(stream, enabledDebrid)
        val size = sizeLabel(stream)
        val source = stream.source.takeIf { it.isNotBlank() }
        val head = listOfNotNull(mark, size).joinToString(" ")
        val line1 = when {
            head.isNotBlank() && source != null -> "$head · $source"
            head.isNotBlank() -> head
            source != null -> source
            else -> "Local Cache"
        }

        val line2 = videoAudioLine(stream)

        val st = status?.lowercase().orEmpty()
        val line3 = when {
            progress >= 100 || st == "complete" -> "✅ 100% on USB"
            st == "downloading" && progress > 0 -> "⬇️ $progress% downloading"
            st == "paused" -> "⬇️ $progress% paused — tap to play/resume"
            progress > 0 -> "⬇️ $progress% on USB"
            else -> null
        }

        val line4 = "⭐ ${releaseName(stream)}"

        return listOfNotNull(line1, line2, line3, line4).joinToString("\n")
    }

    private fun cleanReleaseLine(line: String): String =
        line
            .replace(Regex("""^\[[^\]]+\]\s*"""), "")
            .replace(Regex("""^\[(AD|RD|TB|PM|DL|ED|OC|PU)[+⚡⬇️]\]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { line.trim() }

    /** Short addon branding / quality chips — not a real file name. */
    private fun looksLikeAddonBranding(line: String): Boolean {
        val cleaned = cleanReleaseLine(line).lowercase()
        if (cleaned.isBlank()) return true
        if (Regex(
                """^(torrentio|comet|local\s*cache)([\s·|/\-]*(4k|uhd|2160p|1080p|720p|hdr10?\+?|dv|atmos|dolby|vision|remux|web-?dl))*$""",
            ).matches(cleaned)
        ) {
            return true
        }
        if (Regex(
                """^(4k|uhd|2160p|1080p|720p)([\s·|/\-]*(4k|uhd|2160p|1080p|720p|hdr10?\+?|dv|atmos|dolby|vision))*$""",
            ).matches(cleaned)
        ) {
            return true
        }
        return false
    }

    private fun looksLikeMetaLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        // Torrentio stats: 👤 12 💾 15.2 GB ⚙️ …
        if (Regex("""^[👤💾⚙️🚀⭐🧲⚡⬇️✅+].*""").matches(t) && t.length < 80) return true
        if (Regex("""^[\d./\s]+(GB|MB)\b""", RegexOption.IGNORE_CASE).matches(t)) return true
        return false
    }

    private fun looksLikeRelease(line: String): Boolean {
        if (looksLikeAddonBranding(line) || looksLikeMetaLine(line)) return false
        val cleaned = cleanReleaseLine(line)
        if (cleaned.length < 12) return false
        val t = cleaned.lowercase()
        if (Regex("""\.\d{4}\.""").containsMatchIn(t)) return true
        if (cleaned.count { it == '.' } >= 3 && cleaned.length >= 20) return true
        // Spaced release names with a real codec/source token and enough length.
        if (cleaned.length >= 18 &&
            Regex(
                """\b(2160p|1080p|720p|bluray|blu-ray|bdrip|web-?dl|webrip|remux|hdtv|x264|x265|h\.?265|h\.?264|hevc|truehd|dts-?hd)\b""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(t)
        ) {
            return true
        }
        return false
    }

    private fun streamText(stream: StreamItem): String =
        listOfNotNull(stream.rawName, stream.title, stream.description, stream.filename, stream.label)
            .joinToString(" ")
            .lowercase()
}
