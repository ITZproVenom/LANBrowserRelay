package com.lanbrowserrelay.ui
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lanbrowserrelay.server.LanServerService
class MainActivity:Activity(){
 private var service:LanServerService?=null;private var bound=false
 private lateinit var status:TextView;private lateinit var address:TextView;private lateinit var stats:TextView;private lateinit var logs:TextView;private lateinit var preview:WebView
 private val handler=Handler(Looper.getMainLooper());private val refresher=object:Runnable{override fun run(){refresh();handler.postDelayed(this,1200)}}
 private val connection=object:ServiceConnection{
  override fun onServiceConnected(n:ComponentName?,b:IBinder?){service=(b as? LanServerService.LocalBinder)?.service();bound=true;refresh();loadPreview()}
  override fun onServiceDisconnected(n:ComponentName?){service=null;bound=false}
 }
 @SuppressLint("SetJavaScriptEnabled") override fun onCreate(state:Bundle?){
  super.onCreate(state);window.statusBarColor=Color.rgb(16,19,24);window.navigationBarColor=Color.rgb(16,19,24)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,18,28,18);setBackgroundColor(Color.rgb(16,19,24))}
  fun label(s:String,size:Float,color:Int=Color.WHITE)=TextView(this).apply{text=s;textSize=size;setTextColor(color);setPadding(0,5,0,5)}
  root.addView(label("LAN BROWSER RELAY",24f,Color.rgb(88,217,196)))
  status=label("Starting service…",16f,Color.YELLOW);address=label("LAN URL: discovering…",20f);stats=label("Transfers: idle",14f,Color.LTGRAY)
  root.addView(status);root.addView(address);root.addView(stats)
  logs=label("Waiting for requests…",12f,Color.LTGRAY);val scroll=ScrollView(this);scroll.addView(logs);root.addView(scroll,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
  root.addView(label("Read-only preview",12f,Color.rgb(88,217,196)))
  preview=WebView(this).apply{settings.javaScriptEnabled=true;settings.domStorageEnabled=false;clearCache(true);clearHistory();webViewClient=WebViewClient()}
  root.addView(preview,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,.7f))
  setContentView(root);LanServerService.start(this)
 }
 override fun onStart(){super.onStart();bindService(Intent(this,LanServerService::class.java),connection,Context.BIND_AUTO_CREATE);handler.post(refresher)}
 override fun onStop(){handler.removeCallbacks(refresher);if(bound){unbindService(connection);bound=false};super.onStop()}
 private fun loadPreview(){val s=service?:return;if(s.isRunning()&&preview.url==null)preview.loadUrl("http://127.0.0.1:${s.port()}/")}
 private fun refresh(){val s=service?:return;val running=s.isRunning();status.text=if(running)"● Server running" else "◌ Waiting for LAN";status.setTextColor(if(running)Color.rgb(88,217,196) else Color.YELLOW)
  address.text=if(running)"http://${s.address()}:${s.port()}" else "LAN URL: unavailable"
  stats.text="Active downloads: ${s.downloads().activeCount()} · Relayed: ${s.downloads().totalBytesServed()/1_000_000} MB · Requests: ${s.clients()}"
  logs.text=s.logs().joinToString("
").ifBlank{"Waiting for requests…"};loadPreview()
 }
}
