package app.localcache.torrent

import java.net.URLEncoder
import java.util.Locale

/**
 * Turns Torrentio / Comet torrent rows into magnet URIs.
 *
 * Those addons describe a torrent as `infoHash` + `sources` (trackers and DHT nodes)
 * rather than a link, so the magnet has to be assembled here before libtorrent sees it.
 */
object MagnetLinks {

    /**
     * Added to every magnet. Torrentio usually ships trackers in `sources`, but Comet and
     * some Torrentio mirrors ship none, and a magnet with no trackers can only rely on DHT —
     * which is what made earlier torrent attempts sit at 0 peers forever.
     */
    private val FALLBACK_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://explodie.org:6969/announce",
    )

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32_32 = Regex("^[A-Z2-7]{32}$")

    fun isMagnet(url: String?): Boolean =
        url?.trim()?.startsWith("magnet:", ignoreCase = true) == true

    fun isValidInfoHash(hash: String?): Boolean {
        val h = hash?.trim() ?: return false
        return HEX40.matches(h) || BASE32_32.matches(h.uppercase(Locale.US))
    }

    /** Info hash of a magnet URI, or null when it has none we understand. */
    fun infoHashOf(magnet: String): String? =
        Regex("""xt=urn:btih:([^&]+)""", RegexOption.IGNORE_CASE)
            .find(magnet)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { isValidInfoHash(it) }

    /**
     * Builds a magnet from an addon row. [sources] accepts Torrentio's raw entries
     * (`tracker:udp://…`, `dht:…`) as well as plain announce URLs.
     */
    fun build(infoHash: String, displayName: String?, sources: List<String>): String? {
        val hash = infoHash.trim()
        if (!isValidInfoHash(hash)) return null

        val trackers = LinkedHashSet<String>()
        sources.forEach { raw ->
            val value = raw.trim()
            when {
                value.startsWith("tracker:", ignoreCase = true) ->
                    trackers.add(value.removePrefix("tracker:").removePrefix("TRACKER:").trim())
                value.startsWith("udp://", ignoreCase = true) ||
                    value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true) ||
                    value.startsWith("ws://", ignoreCase = true) ||
                    value.startsWith("wss://", ignoreCase = true) -> trackers.add(value)
                // `dht:<hash>` carries no address — DHT is enabled on the session anyway.
            }
        }
        trackers.addAll(FALLBACK_TRACKERS)

        return buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            displayName?.trim()?.takeIf { it.isNotBlank() }?.let {
                append("&dn=").append(encode(it.take(150)))
            }
            trackers.filter { it.isNotBlank() }.forEach {
                append("&tr=").append(encode(it))
            }
        }
    }

    /** Adds the fallback tracker list to a magnet that arrived with few or none. */
    fun withFallbackTrackers(magnet: String): String {
        val existing = Regex("""[?&]tr=([^&]+)""")
            .findAll(magnet)
            .map { it.groupValues[1] }
            .toSet()
        val missing = FALLBACK_TRACKERS.filterNot { existing.contains(encode(it)) }
        if (missing.isEmpty()) return magnet
        return magnet + missing.joinToString("") { "&tr=${encode(it)}" }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
