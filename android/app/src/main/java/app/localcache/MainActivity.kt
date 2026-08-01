package app.localcache

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.localcache.config.AddonConfig
import app.localcache.model.UsbVolume
import app.localcache.net.LanAddress
import app.localcache.net.LanReachability
import app.localcache.server.PortFinder
import app.localcache.server.RequestLog
import app.localcache.server.ServerManager
import app.localcache.storage.DiskQuota
import app.localcache.storage.DownloadEngine
import app.localcache.storage.UsbDriveDetector
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var ipText: TextView
    private lateinit var usbText: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var verifyJob: Job? = null
    private var statusJob: Job? = null
    private var serverRunning = false
    private var lastVerifyMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverRunning = ServerManager.isRunning()

        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)
        ipText = findViewById(R.id.ipText)
        usbText = findViewById(R.id.usbText)

        findViewById<Button>(R.id.btnRefreshIp).setOnClickListener {
            refreshLanIp(showDialogIfMultiple = true)
        }

        findViewById<Button>(R.id.btnHealthCheck).setOnClickListener {
            runHealthCheck()
        }

        findViewById<Button>(R.id.btnPickUsb).setOnClickListener {
            promptUsbSelection(force = true)
        }

        findViewById<Button>(R.id.btnEditConfig).setOnClickListener {
            promptEditConfigOnTv()
        }

        findViewById<Button>(R.id.btnCancelDownload).setOnClickListener {
            promptCancelDownload()
        }

        findViewById<Button>(R.id.btnDeleteCache).setOnClickListener {
            promptDeleteCache()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            refreshLanIp(showDialogIfMultiple = false)
            if (Prefs.cacheDirPath(this) == null) {
                statusText.text = "Choose a USB drive first"
                promptUsbSelection(force = true)
                return@setOnClickListener
            }
            startLocalServer()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopLocalServer()
        }

        refreshLanIp(showDialogIfMultiple = Prefs.manualLanHost(this) == null)
        refreshUi()

        if (Prefs.cacheDirPath(this) == null) {
            promptUsbSelection(force = false)
        }
    }

    override fun onResume() {
        super.onResume()
        serverRunning = ServerManager.isRunning()
        statusJob?.cancel()
        statusJob = scope.launch {
            while (true) {
                refreshUi()
                delay(3_000)
            }
        }
    }

    override fun onPause() {
        statusJob?.cancel()
        statusJob = null
        super.onPause()
    }

    override fun onDestroy() {
        verifyJob?.cancel()
        statusJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startLocalServer() {
        if (serverRunning && ServerManager.isRunning()) {
            statusText.text = "Server already running on port ${Prefs.serverPort(this)}"
            return
        }

        try {
            statusText.text = "Starting server…"
            val started = ServerManager.start(this)
            CacheForegroundService.start(this)
            serverRunning = true
            statusText.text = buildString {
                append("Server running on port ${started.port}")
                started.portNote?.let { append("\n$it") }
                append("\nOpen Stremio and add the install URL below.")
            }
            refreshUi()
            // Quiet LAN ping after bind (no dialog) — full report is Health check.
            verifyJob?.cancel()
            verifyJob = scope.launch {
                delay(800)
                val lan = LanReachability.verify(
                    this@MainActivity,
                    port = Prefs.serverPort(this@MainActivity),
                )
                lastVerifyMessage = if (lan.ok) "✓ ${lan.message}" else "✗ ${lan.message}"
                refreshUi()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Server start failed", e)
            serverRunning = false
            val tried = PortFinder.candidates.joinToString()
            statusText.text = "Could not start server.\n${e.message}\nPorts tried: $tried"
            Toast.makeText(this, "Bind failed", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Server start failed", e)
            serverRunning = false
            statusText.text = "Server failed: ${e.message}"
        }
    }

    private fun stopLocalServer() {
        CacheForegroundService.stop(this)
        ServerManager.stop()
        serverRunning = false
        lastVerifyMessage = null
        statusText.text = "Server stopped"
        refreshUi()
    }

    private fun refreshLanIp(showDialogIfMultiple: Boolean) {
        val all = Prefs.allDetected(this)
        Prefs.refreshDetectedLanIp(this)

        if (showDialogIfMultiple && all.size > 1 && Prefs.manualLanHost(this) == null) {
            showIpPicker(all)
        }
        refreshUi()
    }

    private fun runHealthCheck() {
        verifyJob?.cancel()
        verifyJob = scope.launch {
            lastVerifyMessage = "Running health check…"
            refreshUi()
            statusText.text = "Running health check…"

            val report = HealthCheck.run(this@MainActivity)
            val message = HealthCheck.formatMessage(this@MainActivity, report)
            lastVerifyMessage = if (report.allOk) {
                "Health: all checks passed"
            } else {
                "Health: ${report.items.count { !it.ok }} issue(s) — see dialog"
            }
            refreshUi()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (report.allOk) "Health check — OK" else "Health check — issues found")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun refreshUi() {
        val used = Prefs.lanHost(this)
        val port = Prefs.serverPort(this)
        val cfg = AddonConfig.load(this)

        statusText.text = when {
            !serverRunning -> "Stopped — open this app and tap Start server before using Stremio"
            !cfg.hasAnyUpstream() -> "Server running — configure Torrentio/Comet manifests next"
            else -> "Server running on port $port"
        }

        urlText.text = buildString {
            appendLine("Install in Stremio (on this TV):")
            appendLine("Stremio → Add-ons → Add Add-on →")
            appendLine(Prefs.stremioInstallUrl(this@MainActivity))
            appendLine()
            appendLine("Configure Add-on (phone/PC on same Wi‑Fi):")
            appendLine(Prefs.settingsUrl(this@MainActivity))
            appendLine()
            appendLine("Test Add-on:")
            appendLine(Prefs.healthUrl(this@MainActivity))
            if (serverRunning) {
                appendLine()
                append(RequestLog.summary())
            }
        }

        ipText.text = buildString {
            appendLine("LAN IP for phone/PC (same Wi‑Fi, no router changes):")
            appendLine("Using: $used:$port")
            Prefs.manualLanHost(this@MainActivity)?.let {
                appendLine("Source: manual choice")
            } ?: Prefs.detectedLanHost(this@MainActivity)?.let {
                appendLine("Source: auto-detected${Prefs.detectedInterface(this@MainActivity)?.let { " ($it)" } ?: ""}")
            } ?: appendLine("Source: could not detect — check Wi‑Fi/Ethernet")
            lastVerifyMessage?.let { appendLine(it) }
            appendLine()
            append(AddonConfig.summaryLine(this@MainActivity))
        }

        val usb = Prefs.usbLabel(this)
        val cache = Prefs.cacheDirPath(this)
        usbText.text = buildString {
            if (usb != null && cache != null) {
                appendLine("USB: $usb")
                appendLine("Movies save to:")
                appendLine(cache)
                appendLine("Config file on USB root: ${AddonConfig.CONFIG_FILE_NAME}")
                Prefs.usbRootPath(this@MainActivity)?.let { appendLine("USB root: $it") }
                appendLine("Space: ${DiskQuota.summary(this@MainActivity)}")
                if (!File(cache).isDirectory) {
                    appendLine("This folder no longer exists — tap Choose USB drive")
                }
                appendLine()
                appendLine("Download:")
                append(DownloadEngine.statusLine())
            } else {
                append("USB: not selected — tap Choose USB")
            }
        }
    }

    private fun promptEditConfigOnTv() {
        val current = AddonConfig.load(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        fun field(hint: String, value: String, multi: Boolean = false): EditText {
            val edit = EditText(this).apply {
                this.hint = hint
                setText(value)
                if (multi) {
                    minLines = 2
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
            }
            layout.addView(edit)
            return edit
        }

        val torrentio = field(
            "Torrentio — one URL per line (optional; prefer phone)",
            current.torrentioManifestUrls.joinToString("\n"),
            multi = true,
        )
        val comet = field(
            "Comet ElfHosted — one URL per line (optional; prefer phone)",
            current.cometManifestUrls.joinToString("\n"),
            multi = true,
        )
        val localComet = field(
            "Comet Local — one URL per line (optional; prefer phone)",
            current.localCometManifestUrls.joinToString("\n"),
            multi = true,
        )
        val debrid = field(
            "Debrid services (comma-separated)",
            current.debridServices.joinToString(", "),
            multi = true,
        )
        val quality = field(
            "streamQuality: 1080p or 4k_sound",
            current.streamQuality,
        )
        val resultMode = field(
            "resultMode: fast (default) or complete",
            current.resultMode,
        )
        val cacheGb = field("cacheMaxGb", current.cacheMaxGb.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        fun lines(edit: EditText): List<String> =
            edit.text.toString().split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotBlank() }

        AlertDialog.Builder(this)
            .setTitle("Edit config on TV")
            .setMessage(
                "To add multiple Torrentio/Comet manifests (needed when each link only has one debrid), " +
                    "use your phone on the same Wi‑Fi — open ${Prefs.settingsUrl(this)} and tap the + buttons. " +
                    "On TV you can still paste one URL per line below if you must.",
            )
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val snapshot = AddonConfig.Snapshot(
                    torrentioManifestUrls = lines(torrentio),
                    cometManifestUrls = lines(comet),
                    localCometManifestUrls = lines(localComet),
                    debridServices = debrid.text.toString().split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() },
                    streamQuality = quality.text.toString().trim(),
                    cacheMaxGb = cacheGb.text.toString().toIntOrNull() ?: Prefs.DEFAULT_CACHE_MAX_GB,
                    resultMode = resultMode.text.toString().trim(),
                )
                statusText.text = AddonConfig.save(this, snapshot, writeUsb = true)
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCancelDownload() {
        if (!DownloadEngine.isBusy()) {
            statusText.text = "Nothing is downloading right now"
            refreshUi()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Cancel this download?")
            .setMessage(DownloadEngine.statusLine() + "\n\nStopping frees the slot so you can play a different stream.")
            .setPositiveButton("Cancel and delete") { _, _ ->
                statusText.text = DownloadEngine.cancelActive(deletePartial = true)
                refreshUi()
            }
            .setNeutralButton("Stop but keep file") { _, _ ->
                statusText.text = DownloadEngine.cancelActive(deletePartial = false)
                refreshUi()
            }
            .setNegativeButton("Keep downloading", null)
            .show()
    }

    private fun promptDeleteCache() {
        val usage = DiskQuota.usage(this)
        if (usage.files == 0) {
            statusText.text = "Cache is already empty"
            refreshUi()
            return
        }

        val gb = usage.bytes / 1_073_741_824.0
        AlertDialog.Builder(this)
            .setTitle("Delete every cached movie?")
            .setMessage(
                "%d file%s using %.1f GB will be removed from the USB drive. This cannot be undone."
                    .format(usage.files, if (usage.files == 1) "" else "s", gb),
            )
            .setPositiveButton("Delete everything") { _, _ ->
                DownloadEngine.cancelActive(deletePartial = true)
                val wiped = DiskQuota.deleteAll(this)
                statusText.text = if (wiped.failed > 0) {
                    "Deleted ${wiped.deleted}, but ${wiped.failed} could not be removed"
                } else {
                    "Deleted %d file%s, freed %.1f GB".format(
                        wiped.deleted,
                        if (wiped.deleted == 1) "" else "s",
                        wiped.freedBytes / 1_073_741_824.0,
                    )
                }
                refreshUi()
            }
            .setNegativeButton("Keep them", null)
            .show()
    }

    private fun showIpPicker(addresses: List<LanAddress>) {
        val labels = addresses.map {
            "${it.address} — ${it.type}${if (it.interfaceName.isNotBlank()) " (${it.interfaceName})" else ""}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose LAN IP for phone/PC")
            .setMessage("Pick the 192.168.x.x address of this TV (for /settings and /health):")
            .setItems(labels) { _, which ->
                Prefs.setManualLanHost(this, addresses[which].address)
                statusText.text = "Using LAN IP ${addresses[which].address}"
                refreshUi()
            }
            .setNegativeButton("Use auto") { _, _ ->
                Prefs.setManualLanHost(this, null)
                refreshUi()
            }
            .show()
    }

    private fun promptUsbSelection(force: Boolean) {
        val drives = UsbDriveDetector.scan(this)

        when {
            drives.isEmpty() -> {
                statusText.text = "No USB drive detected — plug in a pendrive"
                if (force) {
                    AlertDialog.Builder(this)
                        .setTitle("No USB found")
                        .setMessage("Plug a USB drive into the TV, then tap Choose USB again.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            drives.size == 1 -> confirmSingleDrive(drives.first())
            else -> showDrivePicker(drives)
        }
    }

    private fun confirmSingleDrive(drive: UsbVolume) {
        val cacheDir = UsbDriveDetector.resolveWritableCacheDir(this, drive.path)
        val fs = UsbDriveDetector.mountInfo(drive.path)?.fsType ?: "unknown"
        AlertDialog.Builder(this)
            .setTitle("Use this USB drive?")
            .setMessage(
                "${drive.label}\n${drive.path}\nFilesystem: $fs\n\n" +
                    "Movies → ${cacheDir.absolutePath}\n" +
                    "Config → ${AddonConfig.CONFIG_FILE_NAME} on the USB " +
                    "(USB root if your PC can write it; otherwise next to the movies folder)",
            )
            .setPositiveButton("Yes") { _, _ -> applyUsbChoice(drive) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showDrivePicker(drives: List<UsbVolume>) {
        val labels = drives.map { drive ->
            val fs = UsbDriveDetector.mountInfo(drive.path)?.fsType ?: "?"
            "${drive.label} ($fs)\n${drive.path}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose USB drive")
            .setItems(labels) { _, which -> applyUsbChoice(drives[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyUsbChoice(drive: UsbVolume) {
        val cacheDir = UsbDriveDetector.resolveWritableCacheDir(this, drive.path)
        val check = UsbDriveDetector.checkCacheDir(cacheDir)

        if (!check.writable) {
            val explanation = if (check.readOnlyMount) {
                "The drive is mounted read-only. Eject safely from a computer, then replug."
            } else {
                "Android refused the write.\n\n${check.rawError}"
            }
            AlertDialog.Builder(this)
                .setTitle("Cannot write to USB")
                .setMessage("${cacheDir.absolutePath}\n\nFormat: ${check.fsType ?: "unknown"}\n\n$explanation")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (check.fileSizeTooSmall) {
            val fsLabel = check.fsType?.uppercase() ?: "FAT32"
            AlertDialog.Builder(this)
                .setTitle("Format this drive as exFAT")
                .setMessage(
                    "This USB is $fsLabel (max ~${check.maxFileGb?.toInt() ?: 4} GB per file). " +
                        "Movie files are usually larger, so caching will fail.\n\n" +
                        "Do this on a computer (the TV app cannot format the drive safely):\n" +
                        "1. Plug the drive into a PC\n" +
                        "2. Back up anything you need\n" +
                        "3. Format as exFAT\n" +
                        "4. Plug it back into the TV and Choose USB again",
                )
                .setPositiveButton("OK", null)
                .setNeutralButton("Use anyway") { _, _ -> saveUsbChoice(drive, cacheDir) }
                .show()
            return
        }

        saveUsbChoice(drive, cacheDir)
    }

    private fun saveUsbChoice(drive: UsbVolume, cacheDir: File) {
        try {
            Prefs.setUsbSelection(this, cacheDir.absolutePath, drive.label, drive.path)
            val importMsg = AddonConfig.importFromUsb(this, File(drive.path), cacheDir)
            statusText.text = "USB ready: ${drive.label}\n$importMsg"
            refreshUi()
        } catch (e: Exception) {
            Log.e(TAG, "saveUsbChoice failed", e)
            AlertDialog.Builder(this)
                .setTitle("USB setup failed")
                .setMessage(e.message ?: "Unknown error")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
