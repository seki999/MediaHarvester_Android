package com.mediaharvester.extractor

import android.content.Context
import com.mediaharvester.downloader.PublicNetwork
import com.mediaharvester.downloader.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.ByteArrayOutputStream
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

internal object EngineRuntime {
 val lock=ReentrantReadWriteLock()
 private fun directory(context: Context)=File(context.noBackupFilesDir,"youtubedl-android/yt-dlp")
 fun initialize(context: Context) = lock.write {
  val directory=directory(context)
  val installed=EngineFiles.current(directory)
  if(installed==null || installed<EngineFiles.VERSION) {
   EngineFiles.install(directory,context.assets.open("engine/yt-dlp").use { it.readBytes() },EngineFiles.VERSION,EngineFiles.SHA256)
  }
 }
 suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
  val client=PublicNetwork.client()
  suspend fun fetch(url: String, limit: Int): ByteArray {
   return client.newCall(Request.Builder().url(url).build()).awaitResponse().use { response ->
    check(response.isSuccessful) { "引擎更新 HTTP ${response.code}；当前引擎未更改" }
    val body=response.body ?: error("Empty engine response")
    body.byteStream().use { input ->
     val output=ByteArrayOutputStream(); val buffer=ByteArray(8192)
     while(true) {
      currentCoroutineContext().ensureActive()
      val n=input.read(buffer);if(n<0) break
      require(output.size()+n<=limit) { "Engine response exceeds size limit" }
      output.write(buffer,0,n)
     }
     output.toByteArray()
    }
   }
  }
  val release=JSONObject(fetch("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest",2*1024*1024).toString(Charsets.UTF_8))
  val version=release.getString("tag_name")
  require(version.matches(Regex("20\\d{2}\\.\\d{2}\\.\\d{2}"))) { "Unsupported upstream engine version" }
  require(version>=EngineFiles.VERSION) { "Refusing engine downgrade" }
  val base="https://github.com/yt-dlp/yt-dlp/releases/download/$version/"
  val sums=fetch(base+"SHA2-256SUMS",1024*1024).toString(Charsets.UTF_8)
  val checksum=sums.lineSequence().map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size==2 && it[1]=="yt-dlp" }?.get(0) ?: error("Missing upstream engine checksum")
  require(checksum.matches(Regex("[a-f0-9]{64}")))
  val bytes=fetch(base+"yt-dlp",16*1024*1024)
  currentCoroutineContext().ensureActive()
  val writer=lock.writeLock()
  check(writer.tryLock()) { "请先暂停下载和解析，再更新引擎" }
  try {
   EngineFiles.update(directory(context),bytes,version,checksum) {
    val actual=YoutubeDL.execute(YoutubeDLRequest(emptyList()).apply { addOption("--version") },"engine-update-check").out.trim()
    check(actual==version) { "新引擎无法在当前运行时启动，已恢复原引擎" }
   }
  } finally { writer.unlock() }
  version
 }
}
