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

class MainActivity : AppCompatActivity() {
    private var service: LanServerService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as LanServerService.LocalBinder).service()
            bound = true
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<WebView>(R.id.previewWebView).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            clearCache(true)
            clearHistory()
            webViewClient = WebViewClient()
        }
        LanServerService.start(this)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, LanServerService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(refresh)
    }
    override fun onStop() {
        handler.removeCallbacks(refresh)
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    private fun render() {
        val svc = service
        val running = svc?.isRunning() == true
        findViewById<TextView>(R.id.statusText).text = if (running) "RUNNING" else "STARTING"
        findViewById<TextView>(R.id.lanAddressText).text =
            if (running) "http://${svc!!.getIp()}:${svc.getPort()}" else "Finding LAN address…"
        findViewById<TextView>(R.id.clientsText).text = "Clients: ${svc?.getClientCount() ?: 0}"
        val active = svc?.getDownloads()?.active()?.firstOrNull()
        findViewById<TextView>(R.id.speedText).text = active?.let {
            "${it.filename} • ${it.bytes / 1000} KB • ${it.speedBps / 1000} KB/s"
        } ?: "Speed: idle"
        findViewById<TextView>(R.id.logText).text =
            svc?.getLogs()?.joinToString("\n") ?: "Waiting for service…"
        if (running) {
            val preview = findViewById<WebView>(R.id.previewWebView)
            if (preview.tag != svc.getPort()) {
                preview.tag = svc.getPort()
                preview.loadUrl("http://127.0.0.1:${svc.getPort()}/")
            }
        }
    }
}
