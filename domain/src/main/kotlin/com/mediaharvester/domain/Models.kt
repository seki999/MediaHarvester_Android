package com.mediaharvester.domain

import java.net.URI
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

enum class TaskState { QUEUED, DOWNLOADING, MERGING, VERIFYING, SAVING, PAUSED, WAITING_NETWORK, COMPLETED, FAILED, CANCELLED }
object StateMachine {
 private val active = setOf(TaskState.DOWNLOADING, TaskState.MERGING, TaskState.VERIFYING, TaskState.SAVING)
 fun recover(state: TaskState) = if (state in active || state == TaskState.QUEUED || state == TaskState.WAITING_NETWORK) TaskState.PAUSED else state
 fun allows(from: TaskState, to: TaskState): Boolean = from == to || when(from) {
  TaskState.QUEUED -> to in setOf(TaskState.DOWNLOADING, TaskState.PAUSED, TaskState.CANCELLED)
  TaskState.DOWNLOADING -> to in setOf(TaskState.MERGING, TaskState.VERIFYING, TaskState.WAITING_NETWORK, TaskState.FAILED, TaskState.PAUSED, TaskState.CANCELLED)
  TaskState.MERGING -> to in setOf(TaskState.VERIFYING, TaskState.FAILED, TaskState.PAUSED, TaskState.CANCELLED)
  TaskState.VERIFYING -> to in setOf(TaskState.SAVING, TaskState.FAILED, TaskState.PAUSED, TaskState.CANCELLED)
  TaskState.SAVING -> to in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.PAUSED, TaskState.CANCELLED)
  TaskState.PAUSED, TaskState.FAILED, TaskState.WAITING_NETWORK -> to in setOf(TaskState.QUEUED, TaskState.CANCELLED, TaskState.PAUSED)
  TaskState.COMPLETED, TaskState.CANCELLED -> false
 }
}
data class Format(val id: String, val label: String, val ext: String, val size: Long = 0, val height: Int = 0, val audioOnly: Boolean = false)
data class Media(val url: String, val title: String, val mime: String, val thumbnail: String = "", val width: Int = 0, val height: Int = 0, val size: Long = 0, val duration: Double = 0.0, val formats: List<Format> = emptyList(), val website: Boolean = false)
data class TransferProgress(val bytes: Long, val total: Long, val speed: Long)
data class TransferMeta(val etag: String = "", val total: Long = 0)
interface MediaExtractor { suspend fun extract(url: String): List<Media> }
interface DownloadEngine {
 suspend fun download(url: String, part: File, meta: TransferMeta, rate: Long, metadata: suspend (TransferMeta) -> Unit, progress: suspend (TransferProgress) -> Unit): File
}
object Urls {
 fun parse(text: String): List<String> = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).findAll(text).map { validate(it.value) }.distinct().toList().also { require(it.isNotEmpty()) { "No HTTP(S) URL" } }
 fun validate(url: String): String {
  require(url.none { it.isWhitespace() || it.code < 32 }) { "Invalid URL" }
  val uri = URI(url)
  require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && (uri.port == -1 || uri.port in 1..65535)) { "Only HTTP(S) URLs without credentials are supported" }
  return uri.toASCIIString()
 }
}
fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.').take(140).ifBlank { "media" }
fun sha256(file: File): String = file.inputStream().use { input ->
 val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
 while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
 md.digest().joinToString("") { "%02x".format(it) }
}
