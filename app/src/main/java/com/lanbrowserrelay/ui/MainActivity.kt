package com.lanbrowserrelay.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lanbrowserrelay.R
import com.lanbrowserrelay.server.LanServerService

/**
 * TV status screen only. No navigation controls.
 * Service starts automatically. Everything is controlled from the phone web UI.
 */
class MainActivity : AppCompatActivity() {

    private var service: LanServerService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private var previewLoaded = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1200)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as LanServerService.LocalBinder
            service = local.getService()
            bound = true
            updateUi()
            maybeLoadPreview()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val wv = findViewById<WebView>(R.id.previewWebView)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = false
        wv.clearCache(true)
        wv.clearHistory()
        wv.webViewClient = WebViewClient()

        LanServerService.start(this)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, LanServerService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshRunnable)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun maybeLoadPreview() {
        if (previewLoaded) return
        val svc = service ?: return
        if (!svc.isRunning()) return
        val url = "http://127.0.0.1:${svc.getPort()}/"
        findViewById<WebView>(R.id.previewWebView).loadUrl(url)
        previewLoaded = true
    }

    private fun updateUi() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val lanText = findViewById<TextView>(R.id.lanAddressText)
        val speedText = findViewById<TextView>(R.id.speedText)
        val clientsText = findViewById<TextView>(R.id.clientsText)
        val logText = findViewById<TextView>(R.id.logText)

        val running = service?.isRunning() == true
        statusText.text = if (running) "Running" else "Starting…"
        statusText.setTextColor(getColor(if (running) R.color.success else R.color.warning))

        if (running && service != null) {
            val addr = service!!.getLanAddress()
            val port = service!!.getPort()
            lanText.text = "http://$addr:$port"
            clientsText.text = "Clients: ${service!!.getConnectedClients()}"

            val downloads = service!!.getDownloadManager().getActiveDownloads()
            val active = downloads.firstOrNull()
            speedText.text = if (active != null && active.speedBps > 0) {
                val kb = active.speedBps / 1000
                "Speed: $kb KB/s  |  ${active.filename} (${active.bytesTransferred / 1000} KB)"
            } else {
                val total = service!!.getDownloadManager().getTotalBytesServed()
                "Speed: idle  |  Total served: ${total / 1000} KB"
            }

            val logs = service!!.getLogs()
            logText.text = logs.joinToString("\n")
            maybeLoadPreview()
        } else {
            lanText.text = "—"
            speedText.text = "Speed: —"
            clientsText.text = "Clients: 0"
        }
    }
}
