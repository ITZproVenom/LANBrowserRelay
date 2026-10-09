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
import androidx.core.content.ContextCompat
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
    inner class LocalBinder : Binder() { fun service() = this@LanServerService }
    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var downloads: DownloadManager
    private var server: LanHttpServer? = null
    private var ip = "Finding LAN address…"

    override fun onCreate() {
        super.onCreate()
        downloads = DownloadManager()
        clearCaches()
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startServer()
        return START_STICKY
    }

    fun startServer(): Boolean {
        if (server != null) return true
        ip = findLanIp() ?: "0.0.0.0"
        return try {
            startForeground(NOTIFICATION_ID, notification())
            val instance = LanHttpServer(this, Config.DEFAULT_PORT, downloads) { line ->
                logs.add("${System.currentTimeMillis() % 100000}: $line")
                while (logs.size > MAX_LOGS) logs.removeAt(0)
            }
            instance.start(10_000, false)
            server = instance
            record("Server listening at http://$ip:${Config.DEFAULT_PORT}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start LAN server", e)
            record("Start failed: ${e.message}")
            server?.stop()
            server = null
            false
        }
    }

    fun isRunning() = server != null
    fun getIp() = ip
    fun getPort() = Config.DEFAULT_PORT
    fun getDownloads() = downloads
    fun getClientCount() = server?.connectedClients() ?: 0
    fun getLogs() = (server?.recentLogs() ?: logs.toList()).takeLast(50)

    private fun clearCaches() {
        runCatching {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            java.io.File(applicationInfo.dataDir, "app_webview").listFiles()?.forEach { it.deleteRecursively() }
            record("Temporary caches cleared")
        }.onFailure { record("Cache clear warning: ${it.message}") }
    }

    private fun notification(): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LanBrowserApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("LAN Browser Relay")
            .setContentText("Phone browser: http://$ip:${Config.DEFAULT_PORT}")
            .setContentIntent(pending).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    private fun findLanIp(): String? = try {
        val addresses = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
        addresses.firstOrNull { addr ->
            val value = addr.hostAddress ?: return@firstOrNull false
            value.startsWith("192.168.") || value.startsWith("10.") ||
                value.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
        }?.hostAddress ?: addresses.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "LAN address discovery failed", e)
        null
    }

    private fun record(message: String) {
        logs.add("${System.currentTimeMillis() % 100000}: $message")
        while (logs.size > MAX_LOGS) logs.removeAt(0)
        Log.i(TAG, message)
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        server?.stop()
        server = null
        stopForeground(true)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LanServerService"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_LOGS = 100
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LanServerService::class.java))
        }
    }
}
