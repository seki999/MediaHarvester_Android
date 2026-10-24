package com.mediaharvester.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.mediaharvester.database.*
import com.mediaharvester.domain.*
import com.mediaharvester.downloader.*
import com.mediaharvester.extractor.Extractor
import com.mediaharvester.extractor.FfmpegRunner
import com.mediaharvester.storage.Publisher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

class DownloadCoordinator(private val context: Context, private val http: DownloadEngine = HttpEngine()) {
 val database = TaskDatabase.open(context)
 val dao = database.tasks()
 val settings = Settings(context)
 val cookies = CookieVault(context)
 val extractor = Extractor(context, cookies::export)
 val publisher = Publisher(context)
 private val control = Mutex()
 private val publication = Mutex()
 private val running = mutableMapOf<String, Job>()
 private var initialized = false
 private fun dir(id: String) = File(context.filesDir, "tasks/$id").apply { mkdirs() }
 suspend fun initialize() = control.withLock {
  if(initialized) return@withLock
  cookies.clearTemporary()
  dao.all().filter { it.state == "SAVING" }.forEach { t ->
   if(t.outputUri.isNotEmpty() && t.digest.isNotEmpty() && publisher.verify(Uri.parse(t.outputUri),t.digest)) {
    publisher.finish(Uri.parse(t.outputUri)); dao.save(t.copy(state="COMPLETED",speed=0))
   } else { publisher.remove(t.outputUri); dao.save(t.copy(state="PAUSED",outputUri="",speed=0,error="Interrupted while saving; resume to save again")) }
  }
  dao.recover()
  initialized=true
 }
 suspend fun extract(url: String) = extractor.extract(url)
 suspend fun enqueue(media: Media, format: Format?, conversion: String) {
  initialize()
  val p=settings.flow.value
  val ext = if(conversion.isNotEmpty()) conversion else format?.ext ?: media.title.substringAfterLast('.', "")
  val title = if(p.naming=="timestamp") "${System.currentTimeMillis()}_${media.title}" else media.title
  control.withLock {
   val existing=dao.all().lastOrNull { it.url==media.url && it.selector==format?.id.orEmpty() && it.conversion==conversion && it.destination==p.directory && it.gallery==p.gallery && it.state !in listOf("COMPLETED","CANCELLED") }
   if(existing!=null) {
    if(existing.state in listOf("PAUSED","FAILED","WAITING_NETWORK")) dao.transition(existing.id,existing.state,"QUEUED","正在恢复已有任务并刷新媒体地址")
    return@withLock
   }
   dao.save(TaskEntity(url=media.url,title=title,mime=if(conversion.isNotEmpty() && conversion!="mp4" || format?.audioOnly==true) "audio/${ext.ifEmpty { "mp4" }}" else media.mime,website=media.website,selector=format?.id.orEmpty(),conversion=conversion,total=format?.size?.takeIf { it>0 } ?: media.size,destination=p.directory,gallery=p.gallery))
  }
 }
 suspend fun action(id: String, action: String, reason: String = "") = control.withLock {
  val task=dao.get(id) ?: return@withLock
  val state=TaskState.valueOf(task.state)
  val next=when(action) { "resume","retry" -> TaskState.QUEUED; "cancel" -> TaskState.CANCELLED; else -> TaskState.PAUSED }
  if(!StateMachine.allows(state,next)) return@withLock
  if(next==TaskState.QUEUED && running[id]?.isActive==true) return@withLock
  dao.transition(id,task.state,next.name,if(next==TaskState.PAUSED) reason.ifEmpty { "已暂停，下载数据已保留，可继续下载" } else if(next==TaskState.QUEUED) "正在恢复下载并刷新媒体地址" else "")
  if(next!=TaskState.QUEUED) {
   running[id]?.cancel(); extractor.cancel(id)
   running[id]?.join(); running.remove(id)
   val latest=dao.get(id)!!
   if(latest.outputUri.isNotEmpty() && latest.state!="COMPLETED") { publisher.remove(latest.outputUri); dao.clearOutput(id) }
   if(next==TaskState.CANCELLED) dir(id).deleteRecursively()
  }
 }
 suspend fun pauseAll(reason: String = "") {
  dao.all().filter { it.state in listOf("QUEUED","DOWNLOADING","MERGING","VERIFYING","SAVING","WAITING_NETWORK") }.forEach { action(it.id,"pause",reason) }
 }
 private fun connected(): Boolean {
  val cm=context.getSystemService(ConnectivityManager::class.java)
  val capabilities=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
  return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && (!settings.flow.value.wifi || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
 }
 suspend fun schedule(scope: CoroutineScope): Boolean = control.withLock {
  running.entries.removeAll { it.value.isCompleted }
  if(!connected()) {
   // Suspend active sockets promptly and retain files. Wi-Fi restriction applies to active tasks too.
   running.keys.toList().forEach { id ->
    val t=dao.get(id) ?: return@forEach
    if(t.state=="DOWNLOADING") { dao.transition(id,t.state,"WAITING_NETWORK","Network unavailable or Wi-Fi required"); running[id]?.cancel(); extractor.cancel(id) }
   }
   return@withLock dao.all().any { it.state in listOf("QUEUED","WAITING_NETWORK") } || running.isNotEmpty()
  }
  dao.all().filter { it.state=="WAITING_NETWORK" && running[it.id]?.isCompleted != false }.forEach { dao.transition(it.id,it.state,"QUEUED","") }
  val queued=dao.all().filter { it.state=="QUEUED" && it.id !in running }.take((settings.flow.value.concurrency-running.size).coerceAtLeast(0))
  queued.forEach { t ->
   if(dao.transition(t.id,"QUEUED","DOWNLOADING","")==1) running[t.id]=scope.launch(Dispatchers.IO) { runTask(t.id) }
  }
  running.isNotEmpty() || dao.all().any { it.state in listOf("QUEUED","WAITING_NETWORK") }
 }
 private suspend fun advance(id: String, from: TaskState, to: TaskState) {
  currentCoroutineContext().ensureActive(); check(StateMachine.allows(from,to))
  if(dao.transition(id,from.name,to.name,"")!=1) throw CancellationException("Task state changed")
 }
 private suspend fun runTask(id: String) {
  try {
   var task=dao.get(id) ?: return
   val directory=dir(id)
   var output = if(task.website) {
    extractor.download(task.url,task.selector,task.conversion,File(directory,"website"),id,settings.flow.value.rate,task.mime.startsWith("audio/")) { percent,eta,line ->
     // Callback is synchronous on the native engine reader thread; serialize persistence before returning.
     runBlocking {
      if(line.startsWith("MH_PROGRESS ")) runCatching {
       val p=org.json.JSONObject(line.removePrefix("MH_PROGRESS "))
       dao.progress(id,p.optLong("downloaded_bytes"),p.optLong("total_bytes",p.optLong("total_bytes_estimate")),p.optDouble("speed",0.0).toLong().coerceAtLeast(0))
      }
      if(line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]")) dao.transition(id,"DOWNLOADING","MERGING","")
     }
    }.also { if(dao.get(id)?.state=="DOWNLOADING") advance(id,TaskState.DOWNLOADING,TaskState.MERGING); advance(id,TaskState.MERGING,TaskState.VERIFYING) }
   } else {
    var result: File?=null
    for(attempt in 0..3) {
     try {
      val latest=dao.get(id)!!
      result=http.download(task.url,File(directory,"payload.part"),TransferMeta(latest.etag,latest.total),settings.flow.value.rate,{ dao.metadata(id,it.etag,it.total) },{ dao.progress(id,it.bytes,it.total,it.speed) }); break
     } catch(e: CancellationException) { throw e } catch(e: IOException) {
      if(attempt==3) throw e
      delay(1000L shl attempt)
     }
    }
    advance(id,TaskState.DOWNLOADING,if(task.conversion.isNotEmpty()) TaskState.MERGING else TaskState.VERIFYING)
    result ?: error("No downloaded file")
   }
   currentCoroutineContext().ensureActive()
   if(task.website) {
    // Decode every selected stream before publication; container metadata alone
    // cannot establish that resumed fragments form an intact media stream.
    FfmpegRunner(context).run(listOf("-xerror","-err_detect","explode","-i",output.path,"-map","0:v?","-map","0:a?","-f","null","-"))
   }
   if(!task.website && task.conversion.isNotEmpty()) {
    val converted=File(directory,"converted.${task.conversion}")
    FfmpegRunner(context).convert(output,converted,task.conversion); output=converted
    advance(id,TaskState.MERGING,TaskState.VERIFYING)
   }
   if(task.mime.startsWith("image/")) {
    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeFile(output.path,options)
    require(options.outWidth>0 && options.outHeight>0) { "Invalid or unsupported image" }
    val sample=BitmapFactory.Options().apply { inSampleSize=(maxOf(options.outWidth,options.outHeight)/1024).coerceAtLeast(1) }
    val bitmap=BitmapFactory.decodeFile(output.path,sample) ?: error("Corrupted image")
    bitmap.recycle()
   } else if(!task.website) extractor.verify(output,task.mime.startsWith("audio/"))
   val digest=sha256(output)
   currentCoroutineContext().ensureActive()
   publication.withLock {
   currentCoroutineContext().ensureActive()
   val duplicate=if(task.mime.startsWith("image/")) dao.duplicate(digest)?.takeIf { publisher.verify(Uri.parse(it.outputUri),digest) } else null
   advance(id,TaskState.VERIFYING,TaskState.SAVING)
   task=dao.get(id)!!
   if(duplicate!=null) {
    dao.complete(id,duplicate.outputUri,digest,output.length()); directory.deleteRecursively(); return
   }
   val ext=if(task.website || task.conversion.isNotEmpty()) output.extension else android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(task.mime) ?: task.mime.substringAfter('/').substringBefore('+').replace("jpeg","jpg")
   val name=if(task.title.endsWith(".$ext",true)) task.title else "${task.title}.$ext"
   val outputMime=when(ext.lowercase()) {"m4a"->"audio/mp4";"mp3"->"audio/mpeg";"opus","ogg"->"audio/ogg";"mkv"->"video/x-matroska";"webm"->if(task.mime.startsWith("audio/")) "audio/webm" else "video/webm";else->android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: task.mime}
   val uri=publisher.allocate(name,outputMime,task.destination,task.gallery)
   // Durable publication journal: output URI + checksum are persisted before copying.
   if(dao.journal(id,uri.toString(),digest,outputMime)!=1) { publisher.remove(uri.toString()); throw CancellationException() }
   publisher.write(output,uri,digest)
   currentCoroutineContext().ensureActive()
   val latest=dao.get(id)!!
   if(latest.state!="SAVING") throw CancellationException()
   if(dao.complete(id,uri.toString(),digest,output.length())!=1) throw CancellationException()
   directory.deleteRecursively()
   }
  } catch(e: CancellationException) { throw e }
  catch(e: Exception) {
   val t=dao.get(id) ?: return
   if(t.state in listOf("DOWNLOADING","MERGING","VERIFYING","SAVING")) {
    if(t.state=="SAVING") publisher.remove(t.outputUri)
    // Engine exceptions can contain signed URLs or headers; persist a bounded, redacted message.
    val detail = (e.message ?: e.javaClass.simpleName).replace(Regex("https?://\\S+"),"[URL]").replace(Regex("(?i)(cookie|authorization):[^\n]*"),"[redacted]").takeLast(800)
    val hint = when {
     detail.contains("403") -> "HTTP 403：地址过期或访问被拒绝；重试将刷新地址，请检查登录 Cookie"
     detail.contains("429") -> "HTTP 429：请求过于频繁，请稍后重试"
     detail.contains("timed out",true) || detail.contains("timeout",true) -> "网络超时，请检查网络后重试"
     detail.contains("fragment",true) -> "分片下载失败，未跳过缺失分片；重试前将校验清单"
     else -> "下载失败，请重试"
    }
    val message = "$hint。有效临时数据已保留。\n$detail"
    dao.transition(id,t.state,"FAILED",message)
   }
  }
 }
 suspend fun clearHistory() { dao.clearHistory(); dao.clearRecent() }
 suspend fun clearTemporary() = control.withLock {
  require(running.isEmpty()) { "Pause downloads before clearing temporary data" }
  File(context.filesDir,"tasks").deleteRecursively(); dao.all().filter { it.state !in listOf("COMPLETED","CANCELLED") }.forEach { dao.metadata(it.id,"",0); dao.progress(it.id,0,0,0) }
 }
}
