package app.localcache.stream

import app.localcache.model.StreamPick

data class StreamBuildResult(
    val picks: List<StreamPick>,
    val rawCount: Int,
    val strictCount: Int,
    val usedFallback: Boolean,
)
