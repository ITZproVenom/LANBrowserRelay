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
import com.lanbrowserrelay.LanBrowserApp
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.ui.MainActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
class LanServerService:Service(){
 inner class LocalBinder:Binder(){fun service()=this@LanServerService}
 private var server:LanHttpServer?=null;private val downloads=DownloadManager();private val lines=Collections.synchronizedList(mutableListOf<String>());private var address="Not connected"
 override fun onBind(intent:Intent?):IBinder=LocalBinder()
 override fun onCreate(){super.onCreate();listOfNotNull(cacheDir,externalCacheDir).forEach{d->try{d.listFiles()?.forEach{it.deleteRecursively()}}catch(e:Exception){Log.w("LanServerService","Cache cleanup partial",e)}}}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{startForeground(1001,notification("Starting…"));if(server==null)startServer();return START_STICKY}
 @Synchronized private fun startServer(){
  address=detectAddress()?:"Not connected"
  try{val http=LanHttpServer(this,8080,downloads){line->synchronized(lines){lines.add(line);while(lines.size>100)lines.removeAt(0)}}
   http.start(SOCKET_READ_TIMEOUT,false);server=http
   (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(1001,notification("http://${address}:8080"))
   log("Server started at ${address}:8080")
  }catch(e:Exception){log("Server start failed: ${e.message}")}
 }
 private fun detectAddress():String?{
  val interfaces=try{Collections.list(NetworkInterface.getNetworkInterfaces())}catch(_:Exception){return null}
  for(n in interfaces){if(!n.isUp||n.isLoopback)continue;for(a in Collections.list(n.inetAddresses))if(a is Inet4Address&&!a.isLoopbackAddress&&!a.isLinkLocalAddress){val ip=a.hostAddress?:continue
   if(ip.startsWith("192.168.")||ip.startsWith("10.")||ip.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")))return ip
  }};return null
 }
 private fun notification(msg:String):Notification{
  val p=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return NotificationCompat.Builder(this,LanBrowserApp.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("LAN Browser Relay").setContentText(msg).setContentIntent(p).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
 }
 private fun log(s:String){Log.i("LanServerService",s);synchronized(lines){lines.add(s);while(lines.size>100)lines.removeAt(0)}}
 fun isRunning()=server?.isAlive==true
 fun address()=address
 fun port()=8080
 fun downloads()=downloads
 fun clients()=server?.connectedClients()?:0
 fun logs():List<String> = server?.recentLogs()?:synchronized(lines){lines.toList().takeLast(40)}
 override fun onDestroy(){try{server?.stop()}catch(_:Exception){};server=null;super.onDestroy()}
 companion object{fun start(context:Context)=ContextCompat.startForegroundService(context,Intent(context,LanServerService::class.java))}
}
