package com.kuschelcraft.simplertspstreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service (type camera) that keeps streaming alive with the screen off or the app in
 * the background. Holds a CPU wake lock and a low-latency Wi-Fi lock for the whole session.
 */
class StreamService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationText: String? = null
    private var listenerAdded = false

    private val listener = object : StreamController.Listener {
        override fun onState(state: StreamState) = updateNotification(state)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(StreamController.state), type)
        } catch (e: Exception) {
            AppLog.log("Cannot start foreground service: $e")
            StreamController.fail(getString(R.string.error_foreground, e.message ?: e.toString()))
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        if (!listenerAdded) {
            listenerAdded = true
            StreamController.addListener(listener)
        }
        try {
            StreamController.start(this, ConfigResolver.resolve(this))
        } catch (e: Exception) {
            AppLog.log("Streaming could not be started: $e")
            if (StreamController.state.phase != Phase.ERROR) {
                StreamController.fail(e.message ?: e.toString())
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (listenerAdded) StreamController.removeListener(listener)
        StreamController.stop()
        releaseLocks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleRTSPStreamer:stream").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "SimpleRTSPStreamer:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            AppLog.log("Could not acquire locks: $e")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun contentText(state: StreamState): String {
        val port = state.config?.port ?: ConfigResolver.port(this)
        val ip = NetworkUtils.addresses().firstOrNull()?.ip
        val url = if (ip != null) NetworkUtils.url(ip, port) else getString(R.string.no_network_short)
        return when (state.phase) {
            Phase.RECOVERING -> getString(R.string.notification_recovering)
            Phase.ERROR -> state.message ?: getString(R.string.status_error)
            else -> getString(R.string.notification_text, url, state.clients)
        }
    }

    private fun updateNotification(state: StreamState) {
        val text = contentText(state)
        if (text == lastNotificationText) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(state: StreamState): Notification {
        val text = contentText(state)
        lastNotificationText = text
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.btn_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.kuschelcraft.simplertspstreamer.START"
        const val ACTION_STOP = "com.kuschelcraft.simplertspstreamer.STOP"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1

        /** Starts streaming, or restarts it with the current settings if it is already running. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
