package app.localcache

/** Flavor-specific labels / IDs (Stremio standard vs WuPlay public install). */
object AppVariant {
    val isWuplay: Boolean get() = BuildConfig.WUPLAY_MODE
    val clientName: String get() = BuildConfig.CLIENT_NAME
    val addonId: String get() = BuildConfig.ADDON_ID
    val defaultPort: Int get() = BuildConfig.DEFAULT_PORT
    val prefsFile: String get() = BuildConfig.PREFS_FILE

    fun idleReadyLine(): String = "Idle — ready for $clientName"

    fun resumeHint(): String = "paused — tap stream in $clientName to resume"
}
