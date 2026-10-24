package com.mediaharvester.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.mediaharvester.domain.TaskState
import kotlinx.coroutines.*

class DownloadService: Service() {
 private val coordinator get()=(application as HarvesterApplication).downloads
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private var loop: Job?=null
 private var lock: PowerManager.WakeLock?=null
 override fun onCreate() {
  super.onCreate()
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads","MediaHarvester downloads",NotificationManager.IMPORTANCE_LOW))
 }
 override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
  try { startForeground(1,notification("MediaHarvester"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
  catch(e: Exception) { scope.launch { coordinator.pauseAll("Foreground service unavailable"); stopSelf() }; return START_NOT_STICKY }
  if(intent?.action=="pause" || intent?.action=="cancel") {
   scope.launch {
    coordinator.initialize()
    coordinator.dao.all().filter { it.state in listOf("QUEUED","DOWNLOADING","MERGING","VERIFYING","SAVING","WAITING_NETWORK") }.forEach { coordinator.action(it.id,intent.action!!) }
   }
  }
  if(loop?.isActive!=true) loop=scope.launch {
   var stopReason="Service stopped; retained downloads can be resumed manually"
   try {
    coordinator.initialize()
    lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MediaHarvester:downloads").apply { acquire(6*60*60*1000L) }
    while(isActive) {
     val active=coordinator.schedule(this)
     val tasks=coordinator.dao.all(); val busy=tasks.filter { it.state in listOf("DOWNLOADING","MERGING","VERIFYING","SAVING") }
     val speed=busy.sumOf { it.speed } / 1024
     val types=ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or if(Build.VERSION.SDK_INT>=35 && busy.any {it.state=="MERGING"}) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else 0
     startForeground(1,notification("${busy.size} · $speed KiB/s"),types)
     if(!active) break
     delay(700)
    }
   } catch(e: CancellationException) { throw e }
   catch(e: Exception) { stopReason="Download service unavailable; reopen the app and resume manually" }
   finally {
    // Database writes can fail when storage is full. Always release foreground resources anyway.
    try { withContext(NonCancellable) { runCatching { coordinator.pauseAll(stopReason) } } }
    finally { if(lock?.isHeld==true) lock?.release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
   }
  }
  return START_NOT_STICKY
 }
 private fun notification(text: String): Notification {
  fun action(name: String)=PendingIntent.getService(this,name.hashCode(),Intent(this,DownloadService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return NotificationCompat.Builder(this,"downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MediaHarvester").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
   .addAction(0,getString(com.mediaharvester.ui.R.string.pause),action("pause")).addAction(0,getString(com.mediaharvester.ui.R.string.cancel),action("cancel")).build()
 }
 override fun onTimeout(startId: Int,fgsType: Int) {
  // Stop foreground status immediately; retain persisted state even if cleanup is interrupted.
  loop?.cancel()
  scope.launch(NonCancellable) { runCatching { coordinator.pauseAll("Android foreground time budget exhausted") } }
  stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
 }
 override fun onDestroy() { loop?.cancel(); scope.launch(NonCancellable) { runCatching { coordinator.pauseAll("Service stopped") } }; scope.cancel(); if(lock?.isHeld==true) lock?.release(); super.onDestroy() }
 override fun onBind(intent: Intent?) = null
}
