package app.localcache.model

data class StreamItem(
    val cacheKey: String,
    val source: String,
    val label: String,
    val rawName: String,
    /** Debrid HTTP link, or a magnet URI when [isTorrent]. */
    val url: String,
    val title: String? = null,
    val description: String? = null,
    /** From upstream behaviorHints.filename when present (best release name). */
    val filename: String? = null,
    val qualityScore: Int = 0,
    /** Magnet row from Torrentio/Comet — downloaded peer-to-peer instead of from a debrid. */
    val isTorrent: Boolean = false,
    /** Index of the wanted file inside the torrent, when the addon said which. */
    val fileIndex: Int? = null,
    /** Seeders reported by the addon, used to prefer torrents that will actually move. */
    val seeders: Int? = null,
)

data class StreamPick(
    val stream: StreamItem,
    val slot: String?,
)

data class Upstream(
    val name: String,
    val manifestUrl: String,
) {
    val baseUrl: String
        get() = manifestUrl.replace(Regex("/manifest\\.json$", RegexOption.IGNORE_CASE), "")
}

data class UsbVolume(
    val label: String,
    val path: String,
    val removable: Boolean,
)
