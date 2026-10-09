package com.lanbrowserrelay.download
import com.lanbrowserrelay.DownloadPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
data class Transfer(val id:String,val url:String,val filename:String="Preparing…",val bytes:Long=0,val total:Long?=null,val speed:Long=0,val status:String="STARTING",val error:String?=null,val updated:Long=System.currentTimeMillis())
class DownloadManager {
 private val items=ConcurrentHashMap<String,Transfer>()
 private val reservations=ConcurrentHashMap.newKeySet<String>()
 private val count=AtomicInteger()
 private val served=AtomicLong()
 fun totalBytesServed()=served.get()
 fun activeCount()=items.values.count{it.status=="STARTING"||it.status=="STREAMING"}
 fun recent()=items.values.sortedByDescending{it.updated}.take(30)
 fun begin(id:String,url:String):Boolean {
  if(!reservations.add(id))return false
  if(count.incrementAndGet()>DownloadPolicy.MAX_CONCURRENT){count.decrementAndGet();reservations.remove(id);items[id]=Transfer(id,url,status="FAILED",error="Too many concurrent downloads");trim();return false}
  items[id]=Transfer(id,url);trim();return true
 }
 fun progress(id:String,name:String,total:Long?,bytes:Long,speed:Long){
  if(id !in reservations)return
  items.computeIfPresent(id){_,old->if(old.status=="CANCELLED")old else {
   val n=maxOf(old.bytes,bytes);served.addAndGet((n-old.bytes).coerceAtLeast(0))
   old.copy(filename=name,total=total,bytes=n,speed=speed,status="STREAMING",error=null,updated=System.currentTimeMillis())
  }}
 }
 fun finish(id:String,status:String,bytes:Long,error:String?=null){
  if(reservations.remove(id))count.decrementAndGet()
  items.computeIfPresent(id){_,old->
   val n=maxOf(old.bytes,bytes);served.addAndGet((n-old.bytes).coerceAtLeast(0))
   val s=if(old.status=="CANCELLED"||status=="CANCELLED")"CANCELLED" else status
   old.copy(bytes=n,status=s,speed=0,error=if(s=="CANCELLED")null else error,updated=System.currentTimeMillis())
  };trim()
 }
 fun cancel(id:String){items.computeIfPresent(id){_,old->if(old.status=="STARTING"||old.status=="STREAMING")old.copy(status="CANCELLED",speed=0,updated=System.currentTimeMillis())else old}}
 private fun trim(){if(items.size>30)items.values.filter{it.id !in reservations&&it.status !in setOf("STARTING","STREAMING")}.sortedBy{it.updated}.take(items.size-30).forEach{items.remove(it.id,it)}}
}
