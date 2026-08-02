package app.localcache.storage

import android.content.Context
import android.util.Log
import app.localcache.BuildConfig
import app.localcache.Prefs
import app.localcache.config.AddonConfig
import app.localcache.stream.UpstreamFetcher
import java.io.File
import java.util.Locale

/**
 * USB (recommended) vs app-internal cache folder.
 * Config always lives in Prefs; a JSON copy is written next to the movies folder when possible.
 */
object StorageMode {
    private const val TAG = "StorageMode"
    private const val GB = 1024L * 1024 * 1024

    const val MODE_USB = "usb"
    const val MODE_INTERNAL = "internal"

    const val LIMITATIONS =
        "Local Cache was designed to work via USB. This is a fallback method that will " +
            "compromise on video quality and your TV/Stick might struggle with it."

    fun isInternal(context: Context): Boolean =
        Prefs.storageMode(context) == MODE_INTERNAL

    fun isReady(context: Context): Boolean =
        !Prefs.cacheDirPath(context).isNullOrBlank()

    fun internalCacheDir(context: Context): File =
        File(context.filesDir, "LocalCache")

    fun freeBytesForInternal(context: Context): Long {
        val dir = internalCacheDir(context)
        dir.mkdirs()
        return runCatching { dir.usableSpace }.getOrDefault(0L)
    }

    /** Bytes a new download may use right now (quota room ∩ free space). */
    fun roomBytesForNewFile(context: Context): Long {
        if (!isReady(context)) return 0L
        val usage = DiskQuota.usageBytes(context)
        val quota = DiskQuota.effectiveQuotaBytes(context)
        val free = DiskQuota.freeSpaceOnDrive(context)
        return minOf((quota - usage).coerceAtLeast(0), free)
    }

    fun suggestedQuotaGb(freeBytes: Long): Int {
        val eighty = (freeBytes * 0.8).toLong()
        return (eighty / GB).toInt().coerceIn(1, 4096)
    }

    data class EnableResult(val ok: Boolean, val message: String)

    fun enableInternal(context: Context): EnableResult {
        val free = freeBytesForInternal(context)
        val minFree = BuildConfig.INTERNAL_MIN_FREE_BYTES
        if (!BuildConfig.ALLOW_TINY_INTERNAL && free < minFree) {
            val have = "%.1f".format(Locale.US, free.toDouble() / GB)
            return EnableResult(
                false,
                "Not enough free space on this TV ($have GB free). " +
                    "Need at least ${minFree / GB} GB, or use a USB drive.",
            )
        }

        DownloadEngine.cancelActive(deletePartial = true)

        val dir = internalCacheDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            return EnableResult(false, "Cannot create internal cache folder: $dir")
        }
        val probe = File(dir, ".write-test")
        val writable = runCatching {
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
        if (!writable) {
            return EnableResult(false, "Internal storage is not writable")
        }

        val quotaGb = suggestedQuotaGb(free)
        Prefs.setInternalSelection(context, dir.absolutePath, quotaGb)
        invalidateStreamCaches(context)
        runCatching {
            AddonConfig.writeConfigSafely(context, AddonConfig.load(context))
        }

        val freeGb = "%.1f".format(Locale.US, free.toDouble() / GB)
        val personalNote = if (BuildConfig.ALLOW_TINY_INTERNAL) {
            "\n\n(Personal build: tiny free space allowed for testing.)"
        } else {
            ""
        }
        Log.i(TAG, "internal enabled path=$dir free=$free quotaGb=$quotaGb")
        return EnableResult(
            true,
            "Internal storage ready.\n" +
                "Free: $freeGb GB · Cache limit set to $quotaGb GB (80% of free — changeable).\n\n" +
                LIMITATIONS +
                personalNote,
        )
    }

    fun selectUsb(context: Context, usbRootPath: String): EnableResult {
        val root = File(usbRootPath)
        if (!root.isDirectory) {
            return EnableResult(false, "USB path not found: $usbRootPath")
        }
        val drive = UsbDriveDetector.scan(context).firstOrNull { it.path == usbRootPath }
            ?: return EnableResult(false, "That USB is not plugged in right now.")

        val cacheDir = UsbDriveDetector.resolveWritableCacheDir(context, drive.path)
        val check = UsbDriveDetector.checkCacheDir(cacheDir)
        if (!check.writable) {
            val why = when {
                check.readOnlyMount -> "Drive is mounted read-only."
                else -> check.rawError ?: "Android refused the write."
            }
            return EnableResult(false, "Cannot write to USB (${check.fsType ?: "?"}): $why")
        }

        prepareForUsbSelection(context)
        Prefs.setUsbSelection(context, cacheDir.absolutePath, drive.label, drive.path)
        val importMsg = AddonConfig.importFromUsb(context, File(drive.path), cacheDir)
        val fatNote = if (check.fileSizeTooSmall) {
            "\nWarning: filesystem looks like FAT32 (max ~4 GB/file). Prefer exFAT."
        } else {
            ""
        }
        Log.i(TAG, "USB selected ${drive.path} -> ${cacheDir.absolutePath}")
        return EnableResult(
            true,
            "USB ready: ${drive.label}\n${cacheDir.absolutePath}\n$importMsg$fatNote",
        )
    }

    fun deleteCache(context: Context): EnableResult {
        if (!isReady(context)) {
            return EnableResult(false, "No cache folder selected.")
        }
        DownloadEngine.cancelActive(deletePartial = true)
        val wiped = DiskQuota.deleteAll(context)
        invalidateStreamCaches(context)
        val gb = wiped.freedBytes / GB.toDouble()
        return EnableResult(
            true,
            "Deleted ${wiped.deleted} file(s), freed %.1f GB%s.".format(
                Locale.US,
                gb,
                if (wiped.failed > 0) " (${wiped.failed} failed)" else "",
            ),
        )
    }

    /**
     * Leave internal mode: wipe internal movies, clear cache path.
     * Caller must prompt for USB (or Start stays blocked).
     */
    fun disableInternal(context: Context): String {
        DownloadEngine.cancelActive(deletePartial = true)
        val wiped = wipeInternalFiles(context)
        Prefs.clearStorageSelection(context)
        invalidateStreamCaches(context)
        Log.i(TAG, "internal disabled, wiped=$wiped")
        return "Internal storage off. Cached files on the device were deleted. Choose a USB drive."
    }

    /** Wipe internal cache directory contents (movies), even if current mode is already USB. */
    fun wipeInternalFiles(context: Context): Int {
        val dir = internalCacheDir(context)
        if (!dir.isDirectory) return 0
        var n = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) n++
        }
        return n
    }

    /**
     * Switching to USB: if we were on internal, delete internal movies first.
     */
    fun prepareForUsbSelection(context: Context) {
        if (isInternal(context)) {
            DownloadEngine.cancelActive(deletePartial = true)
            wipeInternalFiles(context)
        }
        invalidateStreamCaches(context)
    }

    private fun invalidateStreamCaches(context: Context) {
        UpstreamFetcher.clearAllCaches()
        CacheRegistry.clearStorageBindings()
        runCatching { LocalLibrary.rehydrate(context) }
        Log.i(TAG, "cleared stream/result caches after storage change")
    }
}
