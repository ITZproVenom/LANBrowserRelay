package com.lanbrowserrelay
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
class LanBrowserApp : Application() {
 override fun onCreate() {
  super.onCreate()
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
   val c = NotificationChannel(CHANNEL, "LAN Browser Relay", NotificationManager.IMPORTANCE_LOW)
   c.description = "Keeps the LAN service available"
   getSystemService(NotificationManager::class.java).createNotificationChannel(c)
  }
 }
 companion object { const val CHANNEL = "lan_browser_relay" }
}
