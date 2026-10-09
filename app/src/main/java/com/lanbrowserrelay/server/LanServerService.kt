package com.lanbrowserrelay.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lanbrowserrelay.LanBrowserApp
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.ui.MainActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class LanServerService : Service() {
    inner class LocalBinder : Binder() { fun service() = this@LanServerService }

    private var server: LanHttpServer? = null
    private val downloads = DownloadManager()
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private val handler = Handler(Looper.getMainLooper())
    private var boundAddress: String? = null
    private var retryAttempt = 0
    private var retryScheduled = false
    private var retryAddress: String? = null
    @Volatile private var address = "Not connected"
    @Volatile private var destroyed = false
    private val retryRunnable = Runnable { retryScheduled = false; refreshNetworkState() }
    private val healthCheck = object : Runnable {
        override fun run() {
            refreshNetworkState()
            if (!destroyed) handler.postDelayed(this, 5_000L)
        }
    }
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var callbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkState()
        override fun onLost(network: Network) = refreshNetworkState()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = refreshNetworkState()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNetworkState()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        cleanStaleCaches()
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
            callbackRegistered = true
        } catch (e: Exception) {
            Log.w("LanServerService", "Could not observe network changes", e)
        }
        handler.postDelayed(healthCheck, 5_000L)
    }

    private fun cleanStaleCaches() {
        listOfNotNull(cacheDir, externalCacheDir).forEach { dir ->
            try { dir.listFiles()?.forEach { it.deleteRecursively() } }
            catch (e: Exception) { Log.w("LanServerService", "Cache cleanup partial", e) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, notification("Starting…"))
        refreshNetworkState()
        return START_STICKY
    }

    @Synchronized
    private fun refreshNetworkState() {
        if (destroyed) return
        address = detectAddress() ?: "Not connected"
        val current = server
        when {
            address == "Not connected" -> {
                if (current != null) {
                    try { current.shutdown() } catch (e: Exception) { Log.w("LanServerService", "Stopping stale listener", e) }
                    server = null
                    boundAddress = null
                }
                handler.removeCallbacks(retryRunnable)
                retryScheduled = false
                retryAddress = null
            }
            current == null -> {
                if (!retryScheduled || retryAddress != address) {
                    handler.removeCallbacks(retryRunnable)
                    retryScheduled = false
                    startServer(address)
                }
            }
            !current.isAliveWithContent() || boundAddress != address -> {
                handler.removeCallbacks(retryRunnable)
                retryScheduled = false
                try { current.shutdown() } catch (e: Exception) { Log.w("LanServerService", "Restarting stale listener", e) }
                server = null
                boundAddress = null
                startServer(address)
            }
            else -> {
                handler.removeCallbacks(retryRunnable)
                retryScheduled = false
                retryAddress = null
            }
        }
        val running = server?.isAliveWithContent() == true
        val message = if (running) "http://$address:8080" else if (address == "Not connected") "Waiting for LAN address" else "Retrying server startup"
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(1001, notification(message)) }
        catch (e: Exception) { Log.w("LanServerService", "Could not refresh service notification", e) }
    }

    @Synchronized
    private fun startServer(host: String) {
        if (destroyed || host == "Not connected") return
        val http = LanHttpServer(this, host, 8080, downloads) { line ->
            synchronized(lines) {
                lines.add(line)
                while (lines.size > 100) lines.removeAt(0)
            }
        }
        try {
            http.start(10_000, false)
            http.startContent()
            if (!http.isAliveWithContent()) throw IllegalStateException("One of the HTTP listeners did not start")
            server = http
            boundAddress = host
            retryAttempt = 0
            handler.removeCallbacks(retryRunnable)
            log("Server started on selected LAN interface $host")
        } catch (e: Exception) {
            try { http.shutdown() } catch (_: Exception) { }
            server = null
            boundAddress = null
            log("Server start failed: ${e.message?.take(160) ?: "bind error"}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (destroyed || address == "Not connected") return
        val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        val delay = delays[minOf(retryAttempt, delays.lastIndex)]
        retryAttempt = (retryAttempt + 1).coerceAtMost(delays.lastIndex)
        retryAddress = address
        retryScheduled = true
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun detectAddress(): String? {
        val activeName: String?
        val activeEthernet: Boolean
        try {
            val network = connectivity.activeNetwork
            val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            val properties = network?.let { connectivity.getLinkProperties(it) }
            activeName = properties?.interfaceName
            activeEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        } catch (e: Exception) {
            Log.w("LanServerService", "Could not inspect active network", e)
            activeName = null
            activeEthernet = false
        }

        val interfaces = try { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            catch (e: Exception) {
                Log.w("LanServerService", "Could not enumerate network interfaces", e)
                return null
            }
        val candidates = interfaces.flatMap { networkInterface ->
            val ethernetName = networkInterface.name.startsWith("eth", true) ||
                networkInterface.name.contains("ethernet", true)
            val isEthernet = ethernetName || (activeEthernet && networkInterface.name == activeName)
            Collections.list(networkInterface.inetAddresses).mapNotNull { address ->
                if (address !is Inet4Address) return@mapNotNull null
                LanAddressSelector.Candidate(
                    interfaceName = networkInterface.name,
                    address = address.hostAddress ?: return@mapNotNull null,
                    isUp = try { networkInterface.isUp } catch (_: Exception) { false },
                    isLoopback = address.isLoopbackAddress || networkInterface.isLoopback,
                    isLinkLocal = address.isLinkLocalAddress,
                    isActive = networkInterface.name == activeName,
                    isEthernet = isEthernet
                )
            }
        }
        return LanAddressSelector.select(candidates)
    }

    private fun notification(message: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, LanBrowserApp.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("LAN Browser Relay")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun log(message: String) {
        Log.i("LanServerService", message)
        synchronized(lines) {
            lines.add(message)
            while (lines.size > 100) lines.removeAt(0)
        }
    }

    fun isRunning() = server?.isAliveWithContent() == true && boundAddress == address
    fun address() = address
    fun port() = 8080
    fun downloads() = downloads
    fun clients() = server?.connectedClients() ?: 0
    fun logs(): List<String> = server?.recentLogs() ?: synchronized(lines) { lines.toList().takeLast(40) }

    @Synchronized
    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacks(retryRunnable)
        retryScheduled = false
        if (callbackRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) }
            catch (e: Exception) { Log.w("LanServerService", "Could not unregister network callback", e) }
            callbackRegistered = false
        }
        try { server?.shutdown() } catch (e: Exception) { Log.w("LanServerService", "Server shutdown incomplete", e) }
        server = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LanServerService::class.java))
    }
}
