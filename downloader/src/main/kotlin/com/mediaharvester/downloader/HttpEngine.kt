package com.mediaharvester.downloader

import com.mediaharvester.domain.*
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PublicNetwork {
 val dns = object : Dns { override fun lookup(host: String): List<InetAddress> = Dns.SYSTEM.lookup(host).also { addresses ->
  if(addresses.isEmpty() || addresses.any {privateAddress(it)}) throw java.net.UnknownHostException("Private network destination blocked")
 } }
 fun privateAddress(a: InetAddress): Boolean {
  val b = a.address
  return a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress ||
   (b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc) ||
   (b.size == 4 && ((b[0].toInt() and 255) == 0 || (b[0].toInt() and 255) == 100 && (b[1].toInt() and 255) in 64..127))
 }
 fun client() = OkHttpClient.Builder().dns(dns).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
  .addNetworkInterceptor { chain ->
   try { Urls.validate(chain.request().url.toString()) } catch(e: IllegalArgumentException) { throw IOException("Blocked redirect URL") }
   if(chain.connection()?.route()?.socketAddress?.address?.let {privateAddress(it)}==true) throw IOException("Private connection blocked")
   chain.proceed(chain.request())
  }.build()
}
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
 continuation.invokeOnCancellation { cancel() }
 enqueue(object : Callback {
  override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
  override fun onResponse(call: Call, response: Response) {
   continuation.resume(response) { _, value, _ -> value.close() }
  }
 })
}
class UnsafeRange(message: String): IOException(message)
@OptIn(InternalCoroutinesApi::class)
class HttpEngine(private val client: OkHttpClient = PublicNetwork.client()): DownloadEngine {
 override suspend fun download(url: String, part: File, meta: TransferMeta, rate: Long, metadata: suspend (TransferMeta) -> Unit, progress: suspend (TransferProgress) -> Unit): File = withContext(Dispatchers.IO) {
  Urls.validate(url); part.parentFile?.mkdirs()
  val coroutine = currentCoroutineContext()
  val checkpoint = ResumeCheckpoint.load(part, url) { coroutine.ensureActive() }
  var validator = checkpoint?.meta?.etag.orEmpty()
  var knownTotal = checkpoint?.meta?.total ?: 0L
  var offset = checkpoint?.bytes ?: 0L
  var hash = checkpoint?.hash ?: MessageDigest.getInstance("SHA-256")
  if (checkpoint == null) ResumeCheckpoint.preserve(part)
  else ResumeCheckpoint.truncateToCheckpoint(part, offset)
  if (offset > 0 && offset == knownTotal) {
   metadata(checkpoint!!.meta); progress(TransferProgress(offset, knownTotal, 0)); return@withContext part
  }
  progress(TransferProgress(offset, knownTotal, 0))
  repeat(2) { attempt ->
   currentCoroutineContext().ensureActive()
   val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
    if (offset > 0) { header("Range", "bytes=$offset-"); header("If-Range", validator) }
   }.build()
   val call = client.newCall(request)
   val handle = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
   try {
    call.awaitResponse().use { response ->
     val etag = response.header("ETag").orEmpty().takeIf { response.headers.values("ETag").size == 1 && ResumeCheckpoint.strongEtag(it) }.orEmpty()
     val body = response.body ?: throw IOException("Empty response")
     val encoded = response.header("Content-Encoding").orEmpty()
     if (encoded.isNotEmpty() && encoded != "identity") throw IOException("Encoded response cannot be safely resumed")
     var total: Long
     var expected: Long
     if (response.code == 206 || response.code == 416) {
      val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
      val start = match?.groupValues?.get(1)?.toLongOrNull()
      val end = match?.groupValues?.get(2)?.toLongOrNull()
      val length = match?.groupValues?.get(3)?.toLongOrNull()
      val valid = response.code == 206 && offset > 0 && etag == validator && start == offset && end != null && length != null && end >= offset && end < length && length == knownTotal && (body.contentLength() < 0 || body.contentLength() == end - offset + 1)
      if (!valid) {
       // 416 is deliberately restarted, never accepted as completion from length alone.
       ResumeCheckpoint.preserve(part)
       offset = 0; validator = ""; knownTotal = 0; hash = MessageDigest.getInstance("SHA-256"); metadata(TransferMeta())
       if (attempt == 1) throw UnsafeRange("Invalid Content-Range or changed validator")
       return@use
      }
      total = length!!; expected = end!! - offset + 1
     } else {
      if (response.code != 200) throw IOException("HTTP ${response.code}")
      if (offset > 0) ResumeCheckpoint.preserve(part)
      offset = 0; hash = MessageDigest.getInstance("SHA-256"); total = body.contentLength().coerceAtLeast(0); expected = body.contentLength()
     }
     val mime = response.header("Content-Type").orEmpty().lowercase()
     if (mime.contains("text/html")) throw IOException("Server returned HTML instead of media")
     // Persist no new validator until stale bytes have been durably removed.
     // Otherwise a crash between metadata and truncation could resume old bytes with a new ETag.
     if(offset==0L) RandomAccessFile(part,"rw").use { it.setLength(0);it.fd.sync() }
     val durableMeta = TransferMeta(etag, total)
     metadata(durableMeta)
     progress(TransferProgress(offset, total, 0))
     val began = System.nanoTime(); var received = 0L; var last = 0L; var checkpointAt = 0L
     RandomAccessFile(part, "rw").use { file ->
      file.setLength(offset); file.seek(offset)
      try {
       body.byteStream().use { input ->
        val bytes = ByteArray(65536)
        while (true) {
         currentCoroutineContext().ensureActive()
         val n = input.read(bytes); if (n < 0) break
         if (expected >= 0 && received + n > expected) throw UnsafeRange("Response exceeds advertised range")
         file.write(bytes, 0, n); hash.update(bytes, 0, n); received += n
         val elapsed = (System.nanoTime() - began) / 1_000_000
         if (rate > 0) delay((received * 1000 / rate - elapsed).coerceAtLeast(0))
         if (elapsed - last >= 300) {
          if (elapsed - checkpointAt >= 2000) {
           file.fd.sync(); ResumeCheckpoint.save(part, url, durableMeta, offset + received, hash); checkpointAt = elapsed
          }
          progress(TransferProgress(offset + received, total, received * 1000 / elapsed.coerceAtLeast(1))); last = elapsed
         }
        }
       }
      } finally {
       // Never suspend here: cancellation must still persist the last synced prefix.
       file.fd.sync(); ResumeCheckpoint.save(part, url, durableMeta, offset + received, hash)
      }
     }
     if (expected >= 0 && received != expected || total > 0 && part.length() != total || part.length() == 0L) throw IOException("Incomplete file")
     currentCoroutineContext().ensureActive()
     progress(TransferProgress(part.length(), total, 0))
     return@withContext part
    }
   } catch(e: IOException) { currentCoroutineContext().ensureActive(); throw e } finally { handle.dispose() }
  }
  throw UnsafeRange("Unsafe resume")
 }
}
