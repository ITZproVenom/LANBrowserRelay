package com.lanbrowserrelay.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lanbrowserrelay.Config
import com.lanbrowserrelay.LanBrowserApp
import com.lanbrowserrelay.R
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.ui.MainActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class LanServerService : Service() {

    private val binder = LocalBinder()
    private var server: LanHttpServer? = null
    private lateinit var downloadManager: DownloadManager
    private var currentPort: Int = Config.DEFAULT_PORT
    private var lanAddress: String = "unknown"
    private val logBuffer = CopyOnWriteArrayList<String>()

    inner class LocalBinder : Binder() {
        fun getService(): LanServerService = this@LanServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        downloadManager = DownloadManager(Config.getMaxDownloadBytes(this))
        clearAppCaches()
    }

    private fun clearAppCaches() {
        try {
            cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            getDir("webview", Context.MODE_PRIVATE)?.listFiles()?.forEach { it.deleteRecursively() }
            Log.i(TAG, "Caches cleared on startup")
            logBuffer.add("Caches cleared on startup")
        } catch (e: Exception) {
            Log.w(TAG, "Cache clear partial: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServer()
            else -> startServer()
        }
        return START_STICKY
    }

    fun startServer(port: Int = Config.getPort(this)): Boolean {
        if (server != null) return true
        currentPort = port
        lanAddress = detectLanAddress() ?: "0.0.0.0"
        return try {
            server = LanHttpServer(
                this,
                currentPort,
                downloadManager,
                logSink = { line -> logBuffer.add(line); while (logBuffer.size > 100) logBuffer.removeAt(0) }
            )
            server?.start()
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Server started on $lanAddress:$currentPort")
            logBuffer.add("Server started on $lanAddress:$currentPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            logBuffer.add("Start failed: ${e.message}")
            server = null
            false
        }
    }

    fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Server stopped")
    }

    fun isRunning(): Boolean = server != null
    fun getLanAddress(): String = lanAddress
    fun getPort(): Int = currentPort
    fun getDownloadManager(): DownloadManager = downloadManager
    fun getConnectedClients(): Int = server?.connectedClients() ?: 0
    fun getLogs(): List<String> = (server?.recentLogs() ?: logBuffer).takeLast(40)

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LanBrowserApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text, lanAddress, currentPort))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun detectLanAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") ||
                            host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
                        ) return host
                    }
                }
            }
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Address detection failed", e)
        }
        return null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    companion object {
        const val TAG = "LanServerService"
        const val ACTION_START = "com.lanbrowserrelay.START"
        const val ACTION_STOP = "com.lanbrowserrelay.STOP"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, LanServerService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LanServerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
