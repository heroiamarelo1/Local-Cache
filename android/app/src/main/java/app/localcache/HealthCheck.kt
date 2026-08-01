package app.localcache

import android.content.Context
import app.localcache.config.AddonConfig
import app.localcache.net.LanReachability
import app.localcache.server.ServerManager
import app.localcache.storage.UsbDriveDetector
import java.io.File

data class HealthItem(
    val ok: Boolean,
    val label: String,
    val detail: String,
)

data class HealthReport(
    val items: List<HealthItem>,
) {
    val allOk: Boolean get() = items.all { it.ok }
}

object HealthCheck {

    suspend fun run(context: Context): HealthReport {
        val items = mutableListOf<HealthItem>()

        val cachePath = Prefs.cacheDirPath(context)
        val cacheDir = cachePath?.let { File(it) }
        if (cacheDir == null) {
            items += HealthItem(false, "USB drive", "Not selected — tap Choose USB drive")
        } else if (!cacheDir.isDirectory) {
            items += HealthItem(false, "USB drive", "Folder missing — Choose USB again")
        } else {
            items += HealthItem(true, "USB drive", Prefs.usbLabel(context) ?: cachePath)
        }

        if (cacheDir != null && cacheDir.isDirectory) {
            val check = UsbDriveDetector.checkCacheDir(cacheDir)
            when {
                !check.writable -> items += HealthItem(
                    false,
                    "USB writable",
                    if (check.readOnlyMount) {
                        "Mounted read-only — eject safely on a PC and replug"
                    } else {
                        check.rawError ?: "Write blocked"
                    },
                )
                check.fileSizeTooSmall -> items += HealthItem(
                    false,
                    "USB filesystem",
                    "${check.fsType?.uppercase() ?: "FAT32"} max ~${check.maxFileGb?.toInt() ?: 4} GB/file — format as exFAT on a PC",
                )
                else -> items += HealthItem(
                    true,
                    "USB filesystem",
                    "${check.fsType ?: "ok"} · writable",
                )
            }
        }

        val cfg = AddonConfig.load(context)
        val upstreams = cfg.upstreams()
        if (upstreams.isEmpty()) {
            items += HealthItem(
                false,
                "Upstreams",
                "No Torrentio/Comet manifests — Edit config or /settings",
            )
        } else {
            items += HealthItem(
                true,
                "Upstreams",
                upstreams.joinToString(", ") { it.name },
            )
        }

        val running = ServerManager.isRunning()
        val port = Prefs.serverPort(context)
        if (!running) {
            items += HealthItem(false, "Server", "Stopped — tap Start server")
        } else {
            items += HealthItem(true, "Server", "Running on port $port")
        }

        if (running) {
            val lan = LanReachability.verify(context, port = port)
            items += HealthItem(
                lan.ok,
                "LAN /health",
                if (lan.ok) {
                    "Phone can use ${Prefs.settingsUrl(context)}"
                } else {
                    lan.message
                },
            )
        } else {
            items += HealthItem(false, "LAN /health", "Start the server first")
        }

        items += HealthItem(
            true,
            "Stremio install",
            Prefs.stremioInstallUrl(context),
        )

        return HealthReport(items)
    }

    fun formatMessage(context: Context, report: HealthReport): String = buildString {
        report.items.forEach { item ->
            append(if (item.ok) "OK  " else "FAIL")
            append("  ")
            append(item.label)
            if (item.detail.isNotBlank()) {
                append(" — ")
                append(item.detail)
            }
            append('\n')
        }
        append('\n')
        if (report.allOk) {
            append("All checks passed.\n")
            append("Stremio: ${Prefs.stremioInstallUrl(context)}")
        } else {
            append("Fix the FAIL items, then run Health check again.")
        }
    }
}
