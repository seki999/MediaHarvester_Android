package com.mediaharvester.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediaharvester.data.*
import com.mediaharvester.database.*
import com.mediaharvester.domain.*
import com.mediaharvester.downloader.*
import com.mediaharvester.extractor.FfmpegRunner
import com.mediaharvester.extractor.Extractor
import com.mediaharvester.storage.Publisher
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IntegrationTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private lateinit var server: MockWebServer
 private lateinit var coordinator: DownloadCoordinator
 private val published=mutableListOf<String>()
 @Before fun setup(): Unit=runBlocking {
  context.stopService(Intent(context,DownloadService::class.java));delay(500)
  server=MockWebServer();server.start()
  coordinator=DownloadCoordinator(context,HttpEngine(OkHttpClient()))
  coordinator.initialize();coordinator.dao.all().filter { it.state !in listOf("COMPLETED","CANCELLED") }.forEach {coordinator.action(it.id,"cancel")}
  (context.applicationContext as HarvesterApplication).downloads=coordinator
 }
 @After fun teardown(): Unit=runBlocking {
  context.stopService(Intent(context,DownloadService::class.java));delay(500)
  coordinator.pauseAll();published.forEach {coordinator.publisher.remove(it)};server.shutdown();coordinator.database.close()
 }
 private fun png(color: Int=android.graphics.Color.BLUE): ByteArray {
  val bitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
  return java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);bitmap.recycle();it.toByteArray() }
 }
 private suspend fun finish(scope: CoroutineScope,ids: List<String>) {
  withTimeout(20000) {
   while(true) {
    coordinator.schedule(scope)
    val tasks=ids.map {coordinator.dao.get(it)!!}
    if(tasks.all{it.state in listOf("COMPLETED","FAILED","CANCELLED")}) {
     tasks.forEach { assertEquals(it.error,"COMPLETED",it.state);published+=it.outputUri };break
    }
    delay(100)
   }
  }
 }
 @Test fun batchImagesDeduplicateAndNeverOverwriteNames(): Unit=runBlocking {
  val a=png();val b=png(android.graphics.Color.RED)
  listOf(a,a,b).forEach {server.enqueue(MockResponse().setHeader("Content-Type","image/png").setHeader("ETag","\"image\"").setBody(Buffer().write(it)))}
  coordinator.settings.save(coordinator.settings.flow.value.copy(concurrency=1))
  val ids=(0..2).map { n ->
   coordinator.enqueue(Media(server.url("/$n.png").toString(),"same-name.png","image/png"),null,"")
   coordinator.dao.all().last().id
  }
  finish(this,ids)
  val tasks=ids.map{coordinator.dao.get(it)!!}
  assertEquals(tasks[0].outputUri,tasks[1].outputUri);assertNotEquals(tasks[0].outputUri,tasks[2].outputUri)
  tasks.forEach {assertTrue(coordinator.publisher.verify(Uri.parse(it.outputUri),it.digest))}
 }
 @Test fun corruptImageCannotComplete(): Unit=runBlocking {
  server.enqueue(MockResponse().setBody("not an image").setHeader("Content-Type","image/png"))
  coordinator.enqueue(Media(server.url("/bad.png").toString(),"bad.png","image/png"),null,"")
  val id=coordinator.dao.all().last().id
  withTimeout(10000){ while(coordinator.dao.get(id)!!.state!="FAILED") {coordinator.schedule(this@runBlocking);delay(100)} }
  assertTrue(coordinator.dao.get(id)!!.outputUri.isEmpty())
 }
 @Test fun cancelledTaskIgnoresLateProgress(): Unit=runBlocking {
  server.enqueue(MockResponse().setHeader("Content-Type","image/png").setHeader("ETag","\"v1\"").setBody(Buffer().write(ByteArray(256*1024))).throttleBody(1024,100,TimeUnit.MILLISECONDS))
  coordinator.enqueue(Media(server.url("/slow.png").toString(),"slow.png","image/png"),null,"")
  val id=coordinator.dao.all().last().id;coordinator.schedule(this)
  delay(500);coordinator.action(id,"cancel");delay(100)
  assertEquals("CANCELLED",coordinator.dao.get(id)!!.state);assertTrue(coordinator.dao.get(id)!!.outputUri.isEmpty())
 }
 @Test fun publicationJournalRecoversCompletedCopy(): Unit=runBlocking {
  val source=File(context.cacheDir,"recovery.png").apply{writeBytes(png())};val digest=sha256(source)
  val publisher=Publisher(context);val uri=publisher.allocate("recovery.png","image/png","",false)
  publisher.write(source,uri,digest);published+=uri.toString()
  val t=TaskEntity(url="https://example.org/recovery",title="recovery.png",mime="image/png",state="SAVING",outputUri=uri.toString(),digest=digest)
  coordinator.dao.save(t)
  val reopened=DownloadCoordinator(context,HttpEngine(OkHttpClient()));reopened.initialize()
  assertEquals("COMPLETED",reopened.dao.get(t.id)!!.state);reopened.database.close();source.delete()
 }
 @Test fun nativeFfmpegMergesRealVideoAndAudio(): Unit=runBlocking {
  coordinator.extractor.initialize()
  val root=File(context.cacheDir,"merge-test").apply{mkdirs()}
  val video=File(root,"v.mp4");val audio=File(root,"a.m4a");val merged=File(root,"merged.mp4")
  val ff=FfmpegRunner(context)
  ff.run(listOf("-f","lavfi","-i","color=c=blue:s=160x120:d=1","-an","-c:v","mpeg4",video.path))
  ff.run(listOf("-f","lavfi","-i","sine=frequency=440:duration=1","-vn","-c:a","aac",audio.path))
  ff.merge(video,audio,merged);coordinator.extractor.verify(merged)
  val retriever=MediaMetadataRetriever();try {retriever.setDataSource(merged.path);assertEquals("yes",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))}finally{retriever.release()}
  assertTrue(merged.length()>0)
  for(format in listOf("mp3","m4a","opus")) {
   val converted=File(root,"converted.$format");ff.convert(merged,converted,format);coordinator.extractor.verify(converted,true)
  }
  root.deleteRecursively()
 }
 @Test fun nativeYtdlpParsesActualDashFormatsAndMergesSelectedTracks(): Unit=runBlocking {
  coordinator.extractor.initialize()
  val root=File(context.cacheDir,"dash-test").apply{mkdirs()}
  val ff=FfmpegRunner(context)
  File(context.filesDir,"native-build.txt").writeText("yt-dlp: "+coordinator.extractor.engineVersion()+"\n"+ff.run(listOf("-version"))+"\n"+ff.run(listOf("-buildconf")))
  android.util.Log.i("MH_NATIVE",File(context.filesDir,"native-build.txt").readText())
  ff.run(listOf("-f","lavfi","-i","color=c=green:s=160x120:d=2","-f","lavfi","-i","sine=frequency=440:duration=2","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-f","dash","-seg_duration","1",File(root,"manifest.mpd").path))
  val denyFragments=java.util.concurrent.atomic.AtomicBoolean(false)
  server.dispatcher=object: Dispatcher() {
   override fun dispatch(request: RecordedRequest): MockResponse {
    val name=request.path.orEmpty().substringBefore('?').substringAfterLast('/')
    val file=File(root,name)
    if(denyFragments.get() && name.endsWith(".m4s")) return MockResponse().setResponseCode(404)
    return if(file.isFile) MockResponse().setHeader("Content-Type",if(name.endsWith(".mpd")) "application/dash+xml" else "video/mp4").setBody(Buffer().write(file.readBytes())) else MockResponse().setResponseCode(404)
   }
  }
  val extractor=Extractor(context,{null},OkHttpClient())
  val media=extractor.extract(server.url("/manifest.mpd").toString()).single()
  val selected=media.formats.first {it.id.contains('+')}
  assertEquals(120,selected.height)
  val outputRoot=File(root,"output")
  val abandoned=File(outputRoot,"attempt-interrupted").apply {mkdirs()}
  val fragments=File(abandoned,"media.mp4.part").apply {writeText("unverified-old-fragments")}
  val merged=extractor.download(media.url,selected.id,"",outputRoot,"dash-test",0){_,_,_->}
  assertEquals("unverified-old-fragments",fragments.readText())
  val requests=server.requestCount
  val recovered=extractor.download(media.url,selected.id,"",outputRoot,"dash-reopen",0){_,_,_->}
  assertEquals(merged.canonicalFile,recovered.canonicalFile)
  assertEquals(requests,server.requestCount)
  denyFragments.set(true)
  val missingOutput=File(root,"missing-fragments")
  val missing=runCatching {extractor.download(media.url,selected.id,"",missingOutput,"dash-missing",0){_,_,_->}}
  assertTrue("Missing DASH fragments must not publish incomplete media",missing.isFailure)
  assertFalse(File(missingOutput,"completed.properties").exists())
  extractor.verify(merged,false,true)
  assertTrue(merged.length()>0)
  root.deleteRecursively()
  Unit
 }
 @Test fun cookieVaultIsEncryptedAndScopedToAuthorizedHost(): Unit=runBlocking {
  val file=File(context.cacheDir,"cookies-test.txt").apply{writeText("# Netscape HTTP Cookie File\n.example.org\tTRUE\t/\tTRUE\t2147483647\tsession\tTEST_SECRET_123\n")}
  val publisher=Publisher(context);val uri=publisher.allocate("cookies-test.txt","text/plain","",false)
  publisher.write(file,uri,sha256(file));published+=uri.toString()
  coordinator.cookies.import(uri,"https://www.example.org/")
  assertNull(coordinator.cookies.export("https://evil.example.net/"))
  val exported=coordinator.cookies.export("https://www.example.org/path")!!
  assertTrue(exported.readText().contains("TEST_SECRET_123"));exported.delete()
  assertFalse(File(context.noBackupFilesDir,"cookies.enc").readBytes().toString(Charsets.UTF_8).contains("TEST_SECRET_123"))
  coordinator.cookies.delete();assertNull(coordinator.cookies.export("https://www.example.org/"));file.delete();Unit
 }
 @Test fun notificationServiceActionsPauseAndCancelTasks(): Unit=runBlocking {
  server.enqueue(MockResponse().setHeader("Content-Type","audio/wav").setBody(Buffer().write(wav(20))).throttleBody(1024,100,TimeUnit.MILLISECONDS))
  coordinator.enqueue(Media(server.url("/pause.wav").toString(),"pause.wav","audio/wav"),null,"")
  val id=coordinator.dao.all().last().id
  val scenario=ActivityScenario.launch(MainActivity::class.java)
  try {
   scenario.onActivity {it.startForegroundService(Intent(it,DownloadService::class.java))};delay(700)
   scenario.onActivity {it.startService(Intent(it,DownloadService::class.java).setAction("pause"))}
   withTimeout(10000){while(coordinator.dao.get(id)!!.state!="PAUSED") delay(100)}
   assertTrue(coordinator.dao.get(id)!!.outputUri.isEmpty())
   delay(1000)
   server.enqueue(MockResponse().setHeader("Content-Type","audio/wav").setBody(Buffer().write(wav(20))).throttleBody(1024,100,TimeUnit.MILLISECONDS))
   coordinator.enqueue(Media(server.url("/cancel.wav").toString(),"cancel.wav","audio/wav"),null,"")
   val other=coordinator.dao.all().last().id
   scenario.onActivity {it.startForegroundService(Intent(it,DownloadService::class.java))};delay(700)
   scenario.onActivity {it.startService(Intent(it,DownloadService::class.java).setAction("cancel"))}
   withTimeout(10000){while(coordinator.dao.get(other)!!.state!="CANCELLED") delay(100)}
   assertTrue(coordinator.dao.get(other)!!.outputUri.isEmpty())
  }finally{scenario.close()}
 }
 @Test fun foregroundServiceDownloadsAfterHomeAndScreenOff(): Unit=runBlocking {
  val bytes=png()
  // Use a real valid WAV with enough payload for the service to remain alive across lock.
  val wav=wav(10)
  server.enqueue(MockResponse().setHeader("Content-Type","audio/wav").setHeader("ETag","\"wav\"").setBody(Buffer().write(wav)).throttleBody(16384,100,TimeUnit.MILLISECONDS))
  coordinator.enqueue(Media(server.url("/tone.wav").toString(),"background.wav","audio/wav"),null,"")
  val id=coordinator.dao.all().last().id
  val scenario=ActivityScenario.launch(MainActivity::class.java)
  scenario.onActivity {it.startForegroundService(Intent(it,DownloadService::class.java))}
  delay(700)
  shell("input keyevent KEYCODE_HOME");shell("input keyevent KEYCODE_SLEEP")
  try {
   withTimeout(25000){while(coordinator.dao.get(id)!!.state !in listOf("COMPLETED","FAILED")) delay(200)}
   val task=coordinator.dao.get(id)!!;assertEquals(task.error,"COMPLETED",task.state);published+=task.outputUri
  }finally{shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");scenario.close()}
 }
 private fun shell(command: String) {instrumentation.uiAutomation.executeShellCommand(command).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()} }
 private fun wav(seconds: Int): ByteArray {
  val count=8000*seconds;val data=java.nio.ByteBuffer.allocate(44+count*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
  data.put("RIFF".toByteArray());data.putInt(36+count*2);data.put("WAVEfmt ".toByteArray());data.putInt(16);data.putShort(1);data.putShort(1);data.putInt(8000);data.putInt(16000);data.putShort(2);data.putShort(16);data.put("data".toByteArray());data.putInt(count*2)
  repeat(count){data.putShort((kotlin.math.sin(it*2*Math.PI*440/8000)*1000).toInt().toShort())};return data.array()
 }
}
