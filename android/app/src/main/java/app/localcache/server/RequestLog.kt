package app.localcache.server

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last stream request the server answered.
 *
 * Without this there is no way to tell "Stremio never called us" from "we answered and it
 * showed nothing" — both look like an empty add-on on the TV.
 */
object RequestLog {
    @Volatile
    private var lastLine: String? = null

    @Volatile
    private var count = 0

    fun record(
        type: String,
        id: String,
        from: String?,
        results: Int,
        onDrive: Int,
        raw: Int = results,
        topFile: String? = null,
    ) {
        count++
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val caller = from?.takeIf { it.isNotBlank() } ?: "unknown"
        val fileBit = topFile?.takeIf { it.isNotBlank() }?.let { " · top: ${it.take(60)}" }.orEmpty()
        lastLine =
            "$time  $type/$id from $caller -> sent $results (raw $raw), $onDrive on USB$fileBit"
    }

    fun summary(): String = when (val line = lastLine) {
        null -> "No stream request received yet — ${app.localcache.AppVariant.clientName} has not called the add-on"
        else -> "Last request (#$count): $line"
    }
}
