package com.lanbrowserrelay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LanBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LAN Browser Relay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the local browser relay running"
                }
            )
        }
    }

    companion object { const val CHANNEL_ID = "lan_browser_relay_service" }
}
