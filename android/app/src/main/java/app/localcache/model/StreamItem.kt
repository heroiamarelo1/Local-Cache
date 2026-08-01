package app.localcache.model

data class StreamItem(
    val cacheKey: String,
    val source: String,
    val label: String,
    val rawName: String,
    val url: String,
    val title: String? = null,
    val description: String? = null,
    /** From upstream behaviorHints.filename when present (best release name). */
    val filename: String? = null,
    val qualityScore: Int = 0,
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
