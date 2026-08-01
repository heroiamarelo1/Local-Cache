package app.localcache.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import app.localcache.model.UsbVolume
import java.io.File

object UsbDriveDetector {

    fun scan(context: Context): List<UsbVolume> {
        val found = linkedMapOf<String, UsbVolume>()

        addFromExternalFilesDirs(context, found)
        addFromStorageManager(context, found)
        scanStorageDirectory(found)

        return found.values.toList()
    }

    private fun addFromExternalFilesDirs(context: Context, found: LinkedHashMap<String, UsbVolume>) {
        val dirs = context.getExternalFilesDirs(null) ?: return
        for (dir in dirs) {
            if (dir == null) continue
            if (!Environment.isExternalStorageRemovable(dir)) continue
            val root = removableRoot(dir) ?: continue
            found[root.absolutePath] = UsbVolume(
                label = root.name.ifBlank { "USB drive" },
                path = root.absolutePath,
                removable = true,
            )
        }
    }

    private fun addFromStorageManager(context: Context, found: LinkedHashMap<String, UsbVolume>) {
        val sm = context.getSystemService(StorageManager::class.java) ?: return
        for (volume in sm.storageVolumes) {
            if (!volume.isRemovable) continue
            val dir = volumeDirectory(volume) ?: continue
            if (!dir.exists() || !dir.canRead()) continue
            val label = volume.getDescription(context)?.toString() ?: dir.name
            found[dir.absolutePath] = UsbVolume(label = label, path = dir.absolutePath, removable = true)
        }
    }

    private fun scanStorageDirectory(found: LinkedHashMap<String, UsbVolume>) {
        val storage = File("/storage")
        if (!storage.isDirectory) return

        storage.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            if (entry.name in setOf("emulated", "self")) return@forEach
            if (!entry.canRead()) return@forEach
            if (entry.name.matches(Regex("^[0-9a-fA-F-]{8,}$")) || entry.name.startsWith("USB", ignoreCase = true)) {
                found.putIfAbsent(
                    entry.absolutePath,
                    UsbVolume(
                        label = entry.name,
                        path = entry.absolutePath,
                        removable = true,
                    )
                )
            }
        }
    }

    private fun removableRoot(file: File): File? {
        var current = file
        repeat(6) {
            val parent = current.parentFile ?: return null
            if (parent.absolutePath == "/storage" || parent.name.matches(Regex("^[0-9A-Fa-f-]{8,}$"))) {
                return if (parent.absolutePath == "/storage") current else parent
            }
            current = parent
        }
        return null
    }

    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        val path = legacyStorageVolumePath(volume) ?: return null
        return File(path)
    }

    private fun legacyStorageVolumePath(volume: StorageVolume): String? {
        return try {
            StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
        } catch (_: Exception) {
            null
        }
    }

    fun defaultCacheDir(usbPath: String): File = File(usbPath, "LocalCache")

    /**
     * App-specific folder on the USB stick — writable without special permissions on Android 10+.
     * Falls back to [usbPath]/LocalCache if no matching external-files dir is found.
     */
    fun resolveWritableCacheDir(context: Context, usbPath: String): File {
        val dirs = context.getExternalFilesDirs(null) ?: return defaultCacheDir(usbPath)
        for (dir in dirs) {
            if (dir == null) continue
            if (dir.absolutePath.startsWith(usbPath.trimEnd('/'))) {
                return File(dir, "LocalCache")
            }
        }
        return defaultCacheDir(usbPath)
    }

    data class Relocation(
        val dir: File,
        val label: String,
        val reused: Boolean,
        val usbRootPath: String,
    )

    /**
     * The saved path can stop existing for two reasons: the drive came back under a new
     * mount point, or it was reformatted (which changes its UUID, and therefore the whole
     * /storage/UUID path). Either way the cache silently looked empty, so find the drive
     * again — reusing an existing LocalCache folder if one is there.
     */
    fun relocateCacheDir(context: Context): Relocation? {
        val drives = scan(context)

        for (volume in drives) {
            val existing = listOf(
                resolveWritableCacheDir(context, volume.path),
                defaultCacheDir(volume.path),
            ).firstOrNull { it.isDirectory }

            if (existing != null) {
                return Relocation(existing, volume.label, reused = true, usbRootPath = volume.path)
            }
        }

        // Nothing to reuse. With a single stick plugged in there is no ambiguity about
        // where movies belong, so adopt it instead of leaving the app pointing at nothing.
        val only = drives.singleOrNull() ?: return null
        val dir = resolveWritableCacheDir(context, only.path)
        if (!dir.exists() && !dir.mkdirs()) return null
        return Relocation(dir, only.label, reused = false, usbRootPath = only.path)
    }

    data class WriteCheck(
        val writable: Boolean,
        val readOnlyMount: Boolean,
        val fsType: String?,
        val maxFileGb: Double?,
        val rawError: String?,
    ) {
        /** FAT32 tops out at 4 GB per file, which is useless for a 20 GB movie. */
        val fileSizeTooSmall: Boolean get() = maxFileGb != null
    }

    fun checkCacheDir(dir: File): WriteCheck {
        val mount = mountInfo(dir.absolutePath)
        val fsType = mount?.fsType
        val maxFileGb = maxFileSizeGb(fsType)

        if (!dir.exists() && !dir.mkdirs()) {
            return WriteCheck(false, mount?.readOnly == true, fsType, maxFileGb, "Could not create folder")
        }

        val test = File(dir, ".write_test")
        return try {
            test.writeText("ok")
            test.delete()
            WriteCheck(true, false, fsType, maxFileGb, null)
        } catch (e: Exception) {
            val message = e.message ?: "Write blocked"
            val readOnly = mount?.readOnly == true || message.contains("EROFS", ignoreCase = true) ||
                message.contains("Read-only", ignoreCase = true)
            WriteCheck(false, readOnly, fsType, maxFileGb, message)
        }
    }

    data class MountInfo(val mountPoint: String, val fsType: String, val readOnly: Boolean)

    /** Reads /proc/mounts to find how the drive holding [path] is mounted. */
    fun mountInfo(path: String): MountInfo? = runCatching {
        File("/proc/mounts").readLines()
            .mapNotNull { line ->
                val parts = line.split(" ")
                if (parts.size < 4) return@mapNotNull null
                val mountPoint = parts[1].replace("\\040", " ")
                if (mountPoint == "/" || !path.startsWith(mountPoint)) return@mapNotNull null
                MountInfo(
                    mountPoint = mountPoint,
                    fsType = parts[2],
                    readOnly = parts[3].split(",").contains("ro"),
                )
            }
            // The longest matching mount point is the drive itself.
            .maxByOrNull { it.mountPoint.length }
    }.getOrNull()

    /** Maximum single-file size the filesystem allows, or null when there is no low limit. */
    fun maxFileSizeGb(fsType: String?): Double? = when (fsType?.lowercase()) {
        "vfat", "msdos", "fat", "fat32" -> 4.0
        else -> null
    }
}
