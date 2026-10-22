package com.mediaharvester.extractor
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.*
import java.io.File

class FfmpegRunner(private val context: Context) {
 @OptIn(InternalCoroutinesApi::class)
 suspend fun run(arguments: List<String>): String = withTimeout(30*60*1000L) { withContext(Dispatchers.IO) {
  require(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)<=4096) { "FFmpeg runtime requires 4 KiB pages" }
  FFmpeg.init(context)
  val root=File(context.noBackupFilesDir,"youtubedl-android/packages")
  val process=ProcessBuilder(listOf(File(context.applicationInfo.nativeLibraryDir,"libffmpeg.so").path,"-nostdin","-y","-v","error")+arguments).redirectErrorStream(true).apply {
   environment()["LD_LIBRARY_PATH"]=File(root,"ffmpeg/usr/lib").path+":"+File(root,"python/usr/lib").path
  }.start()
  val handle=currentCoroutineContext().job.invokeOnCompletion(onCancelling=true,invokeImmediately=true) { if(it!=null) process.destroyForcibly() }
  try {
   val log=StringBuilder()
   process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if(log.length<8192) log.appendLine(it) } }
   currentCoroutineContext().ensureActive()
   check(process.waitFor()==0) { "FFmpeg failed; unsupported format or damaged media" }
   log.toString()
  } finally { handle.dispose(); if(process.isAlive) process.destroyForcibly() }
 } }
 suspend fun convert(input: File,output: File,format: String) {
  require(format in listOf("mp3","m4a","opus","mp4"))
  val args=if(format=="mp4") listOf("-c:v","libx264","-c:a","aac","-movflags","+faststart") else listOf("-vn","-c:a",when(format){"mp3"->"libmp3lame";"m4a"->"aac";else->"libopus"})
  run(listOf("-i",input.path)+args+output.path)
 }
 suspend fun merge(video: File,audio: File,output: File) { run(listOf("-i",video.path,"-i",audio.path,"-map","0:v:0","-map","1:a:0","-c","copy","-movflags","+faststart",output.path)) }
}
