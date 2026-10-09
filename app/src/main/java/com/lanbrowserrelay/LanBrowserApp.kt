package com.lanbrowserrelay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LanBrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LAN Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for the LAN Browser Relay service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "lan_server_channel"
    }
}
