package com.mediaharvester.extractor

import android.content.Context
import android.media.MediaMetadataRetriever
import com.mediaharvester.domain.*
import com.mediaharvester.downloader.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.concurrent.read

class Extractor(private val context: Context, private val cookies: (String) -> File?, private val client: OkHttpClient = PublicNetwork.client()): MediaExtractor {
 private val initLock = Any()
 @Volatile private var ready = false
 fun initialize() { require(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)<=4096) { "Bundled media runtime requires 4 KiB pages; direct HTTP and static images remain available" }; if (!ready) synchronized(initLock) { if (!ready) { YoutubeDL.init(context); FFmpeg.init(context); EngineRuntime.initialize(context); ready = true } } }
 @OptIn(InternalCoroutinesApi::class)
 override suspend fun extract(url: String): List<Media> = withTimeout(90_000) {
  withContext(Dispatchers.IO) {
   Urls.validate(url)
   val cookieFile=cookies(url)
   try {
   var images = emptyList<Media>()
   if (cookieFile == null) {
    val call = client.newCall(Request.Builder().url(url).build())
    val handle=currentCoroutineContext().job.invokeOnCompletion(onCancelling=true,invokeImmediately=true) { if(it!=null) call.cancel() }
    try { call.awaitResponse().use { response ->
     val body = response.body ?: error("Empty response")
     val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
     if (response.isSuccessful && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) && !mime.contains("mpegurl") && !mime.contains("dash")) {
      return@withContext listOf(Media(response.request.url.toString(), response.request.url.pathSegments.last().ifBlank { "media" }, mime, if (mime.startsWith("image/")) url else "", size = body.contentLength().coerceAtLeast(0)))
     }
     if (response.isSuccessful && mime.contains("html")) {
      val buffer=java.io.ByteArrayOutputStream()
      val input=body.byteStream(); val chunk=ByteArray(65536)
      while(buffer.size()<=5*1024*1024) { currentCoroutineContext().ensureActive(); val n=input.read(chunk); if(n<0) break; buffer.write(chunk,0,n) }
      val bytes=buffer.toByteArray()
      require(bytes.size <= 5 * 1024 * 1024) { "HTML exceeds 5 MiB limit" }
      images = HtmlImages.extract(bytes.toString(Charsets.UTF_8), response.request.url.toString())
     }
    } } finally { handle.dispose() }
   }
   try { website(url,cookieFile) + images } catch (e: CancellationException) { throw e } catch (e: Exception) { if (images.isEmpty()) throw e else images }
   } finally { cookieFile?.delete() }
  }
 }
 private suspend fun website(url: String, cookieFile: File?): List<Media> {
  initialize()
  val request = YoutubeDLRequest(url).apply { addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-warnings"); addOption("--playlist-end", "100"); addOption("--socket-timeout", "20"); addOption("--retries", "1"); cookieFile?.let { addOption("--cookies", it.absolutePath) } }
  val id = UUID.randomUUID().toString()
  val json = execute(request, id)
  val root = JSONObject(json)
  val entries = root.optJSONArray("entries")
  return if (entries != null) (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.filter { !it.optBoolean("has_drm") }.mapNotNull { parse(it, url) } else listOfNotNull(parse(root, url))
 }
 private fun parse(json: JSONObject, fallback: String): Media? {
  if (json.optBoolean("has_drm") || json.optBoolean("is_live")) return null
  val formats = json.optJSONArray("formats") ?: return null
  val raw = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }.filter { !it.optBoolean("has_drm") }
  val audio = raw.filter { it.optString("vcodec", "none") == "none" && it.optString("acodec", "none") != "none" }.maxByOrNull { it.optDouble("abr", 0.0) }
  val options = raw.mapNotNull { f ->
   val hasVideo = f.optString("vcodec", "none") != "none"
   val hasAudio = f.optString("acodec", "none") != "none"
   if (!hasVideo && !hasAudio || hasVideo && !hasAudio && audio == null) return@mapNotNull null
   val id = f.optString("format_id")
   if (!id.matches(Regex("[a-zA-Z0-9_.-]+"))) return@mapNotNull null
   val selector = if (hasVideo && !hasAudio) "$id+${audio!!.optString("format_id")}" else id
   val height = f.optInt("height")
   Format(selector, if (hasVideo) "${height.takeIf { it > 0 }?.toString()?.plus("p") ?: f.optString("resolution", "video")}  ·  ${f.optString("ext")}  ·  $id" else "Audio  ·  ${f.optString("ext")}  ·  $id", f.optString("ext"), f.optLong("filesize", f.optLong("filesize_approx")), height, !hasVideo)
  }.sortedWith(compareByDescending<Format> { it.height }.thenByDescending { it.size })
  if (options.isEmpty()) return null
  return Media(json.optString("webpage_url", fallback), json.optString("title", "media"), "video/mp4", json.optString("thumbnail"), duration = json.optDouble("duration", 0.0), formats = options, website = true)
 }
 @OptIn(InternalCoroutinesApi::class)
 suspend fun execute(request: YoutubeDLRequest, id: String, progress: (Float, Long, String) -> Unit = { _,_,_ -> }): String = withContext(Dispatchers.IO) {
  val handle = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) YoutubeDL.destroyProcessById(id) }
  val coroutine=currentCoroutineContext()
  try { coroutine.ensureActive(); EngineRuntime.lock.read { coroutine.ensureActive(); YoutubeDL.execute(request, id, progress).out } } finally { handle.dispose() }
 }
 suspend fun download(url: String, selector: String, conversion: String, directory: File, id: String, rate: Long, audioOnly: Boolean = false, attempt: Int = 0, requestedHeight: Int = 0, progress: (Float, Long, String) -> Unit): File {
  initialize(); directory.mkdirs()
  val cookieFile=cookies(url)
  var desiredHeight=requestedHeight
  try {
  // Resolve fresh signed URLs on every attempt. Only reuse fragment journals for an
  // identical selected manifest; changed signatures conservatively isolate old bytes.
  val youtubeClient=if(YoutubePolicy.isYoutube(url)) YoutubePolicy.client(attempt,cookieFile!=null) else null
  val selection=when {
   attempt==0 -> selector
   audioOnly -> "$selector/ba"
   desiredHeight>0 -> "$selector/bv*[height=$desiredHeight]+ba/b[height=$desiredHeight]"
   else -> selector
  }
  val probe = YoutubeDLRequest(url).apply {
   youtubeClient?.let { addOption("--extractor-args", "youtube:player_client=$it") }
   addOption("--dump-single-json"); addOption("--skip-download"); addOption("--no-playlist")
   addOption("-f", selection); addOption("--socket-timeout", "30")
   cookieFile?.let { addOption("--cookies", it.absolutePath) }
  }
  val info = JSONObject(execute(probe, id))
  val selected = info.optJSONArray("requested_formats")
  val streams = if(selected == null) listOf(info) else (0 until selected.length()).map { selected.getJSONObject(it) }
  if(desiredHeight==0) desiredHeight=streams.maxOfOrNull { it.optInt("height",0) } ?: 0
  val identity = streams.joinToString("\n") { f ->
   listOf(info.optString("id"), f.optString("format_id"), f.optString("vcodec"), f.optString("acodec"), f.optString("filesize"),
    f.optString("protocol"), f.optString("manifest_url"), f.optString("fragment_base_url"), f.optJSONArray("fragments")?.toString() ?: "").joinToString("|")
  } + "|" + conversion
  val fragmented = streams.any { it.optString("protocol").contains("m3u8") || it.optString("protocol").contains("dash") || it.has("fragments") }
  val invalidJournal = directory.listFiles().orEmpty().filter { it.extension == "ytdl" }.any {
   runCatching { JSONObject(it.readText()).getJSONObject("downloader").getJSONObject("current_fragment").getInt("index") >= 0 }.getOrDefault(false).not()
  }
  // Without an explicit fragment list, cross-session equivalence cannot be proven.
  val safeIdentity = identity + if(invalidJournal || fragmented && streams.any { !it.has("fragments") }) UUID.randomUUID().toString() else ""
  ResumeGuard.prepare(directory, safeIdentity)
  val request = YoutubeDLRequest(url).apply {
   youtubeClient?.let { addOption("--extractor-args", "youtube:player_client=$it") }
   if(YoutubePolicy.isYoutube(url)) addOption("--http-chunk-size", "10M")
   addOption("--newline"); addOption("--progress-template", "download:MH_PROGRESS %(progress)j"); addOption("--no-playlist"); addOption("--continue"); addOption("--part"); addOption("--abort-on-unavailable-fragments"); addOption("--fragment-retries", "10"); addOption("--extractor-retries", "3"); addOption("--retry-sleep", "http:exp=1:30"); addOption("--retry-sleep", "fragment:exp=1:30"); addOption("--no-mtime"); addOption("--socket-timeout", "30"); addOption("--retries", "10")
   addOption("-f", selection); addOption("--merge-output-format", "mp4/mkv"); addOption("-o", File(directory, "media.%(ext)s").absolutePath)
   if (conversion.isNotEmpty()) { addOption("-x"); addOption("--audio-format", conversion) }
   if (rate > 0) addOption("--limit-rate", rate.toString())
   cookieFile?.let { addOption("--cookies", it.absolutePath) }
  }
  execute(request, id, progress)
  currentCoroutineContext().ensureActive()
  val output = directory.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && !it.name.contains("Frag") && it.extension !in listOf("part", "ytdl", "json", "temp") }?.singleOrNull() ?: error("Missing merged output")
  val audioOutput=audioOnly || conversion.isNotEmpty() || !selector.contains('+') && output.extension in listOf("m4a","mp3","opus","ogg","wav")
  verify(output,audioOutput,!audioOutput)
  return output
  } catch(e: CancellationException) { throw e }
  catch(e: Exception) {
   val message=e.message.orEmpty()
   if(YoutubePolicy.retry(message,attempt)) {
    delay(if(message.contains("429")) 30_000L else 2_000L shl attempt)
    return download(url,selector,conversion,directory,id,rate,audioOnly,attempt+1,desiredHeight,progress)
   }
   throw e
  } finally { cookieFile?.delete() }
 }
 fun verify(file: File, audio: Boolean = false, requireAudio: Boolean = false) {
  require(file.length() > 0) { "Empty media" }
  val retriever = MediaMetadataRetriever()
  try {
   retriever.setDataSource(file.absolutePath)
   require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) { "Invalid media duration" }
   require(retriever.extractMetadata(if (audio) MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO else MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "Missing output stream" }
   if(requireAudio) require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)=="yes") { "Merged audio missing" }
  } finally { retriever.release() }
 }
 suspend fun updateEngine(): String { initialize(); return EngineRuntime.update(context) }
 suspend fun engineVersion(): String { initialize();return execute(YoutubeDLRequest(emptyList()).apply {addOption("--version")},"version-check") }
 fun cancel(id: String) { if (ready) YoutubeDL.destroyProcessById(id) }
}
