package app.localcache.stream

import app.localcache.config.AddonConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object StreamResponseBuilder {

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    /**
     * Official Stremio drops plain http / non-MP4 streams unless notWebReady is set.
     * WuPlay was looser, which is why the same JSON "worked" there.
     */
    private fun behaviorHints(filename: String?): JSONObject =
        JSONObject()
            .put("notWebReady", true)
            .put("bingeGroup", "local-cache")
            .also { hints ->
                if (!filename.isNullOrBlank()) hints.put("filename", filename.take(180))
            }

    fun configureFirstJson(lanHost: String, port: Int): String {
        val streams = JSONArray().put(
            JSONObject()
                .put("name", "Local Cache")
                .put(
                    "description",
                    "Configure add-on first\n" +
                        "Add your Torrentio and Comet manifest.json URLs " +
                        "(USB config, Edit config on TV, or phone /settings).",
                )
                .put("externalUrl", "http://$lanHost:$port/settings"),
        )
        return JSONObject().put("streams", streams).toString()
    }

    fun toJson(
        lanHost: String,
        port: Int,
        build: StreamBuildResult,
        progressOf: (String) -> Int,
        statusOf: (String) -> String? = { null },
        videoHost: String = lanHost,
        enabledDebrid: List<String> = AddonConfig.ALL_DEBRID_SERVICES,
        configured: Boolean = true,
    ): String {
        val picks = build.picks
        val streams = JSONArray()

        if (picks.isEmpty()) {
            val description = when {
                !configured ->
                    "Configure Torrentio/Comet manifest URLs first (USB config or /settings)."
                build.rawCount == 0 ->
                    "Comet/Torrentio returned nothing. Check internet on the TV and open /health or /test."
                build.strictCount == 0 ->
                    "Found ${build.rawCount} streams but none passed filters for the current quality mode."
                else ->
                    "No streams to show after filtering."
            }
            streams.put(
                JSONObject()
                    .put("name", "Local Cache")
                    .put("description", "No matching streams\n$description")
                    .put("externalUrl", "http://$lanHost:$port/settings"),
            )
        } else {
            picks.forEach { pick ->
                val progress = progressOf(pick.stream.cacheKey)
                val status = statusOf(pick.stream.cacheKey)
                val name = StreamLabelFormatter.streamName(
                    pick.stream,
                    progress,
                    status,
                    enabledDebrid,
                )
                val description = StreamLabelFormatter.streamDescription(
                    pick.stream,
                    progress,
                    status,
                    enabledDebrid,
                )
                val videoUrl =
                    "http://$videoHost:$port/video/${encodePathSegment(pick.stream.cacheKey)}"

                streams.put(
                    JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put("url", videoUrl)
                        .put(
                            "behaviorHints",
                            behaviorHints(StreamLabelFormatter.releaseName(pick.stream)),
                        ),
                )
            }
        }

        return JSONObject().put("streams", streams).toString()
    }
}
