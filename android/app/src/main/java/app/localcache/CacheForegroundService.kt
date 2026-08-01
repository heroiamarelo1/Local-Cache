package app.localcache

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.localcache.server.ServerManager
import app.localcache.storage.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the user is over in Stremio watching.
 *
 * Without this the HTTP server and the USB download both live only in the activity's
 * process, which Android TV freezes as soon as the app leaves the screen — so downloads
 * never finished and nothing was ever written to the drive.
 */
class CacheForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var notifyJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local Cache",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        // The activity normally starts the server first; this covers a restart by Android.
        if (!ServerManager.isRunning()) {
            try {
                ServerManager.start(this)
            } catch (e: Exception) {
                Log.e(TAG, "server start failed", e)
            }
        }

        startNotificationUpdates()
        return START_STICKY
    }

    private fun startNotificationUpdates() {
        notifyJob?.cancel()
        notifyJob = scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            while (true) {
                val port = ServerManager.activePort() ?: Prefs.serverPort(this@CacheForegroundService)
                val text = "${DownloadEngine.statusLine()} · port $port"
                runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
                delay(5_000)
            }
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local Cache running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalCache::download").apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    override fun onDestroy() {
        notifyJob?.cancel()
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        ServerManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CacheForegroundService"
        private const val CHANNEL_ID = "local_cache_release"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 8L * 60 * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, CacheForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CacheForegroundService::class.java))
        }
    }
}
