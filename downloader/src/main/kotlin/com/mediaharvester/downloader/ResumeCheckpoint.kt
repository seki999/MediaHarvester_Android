package com.mediaharvester.downloader

import com.mediaharvester.domain.TransferMeta
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** File data is synced before this atomic journal is replaced. Missing/torn journals never authorize append. */
object ResumeCheckpoint {
 data class Verified(val meta: TransferMeta, val bytes: Long, val hash: MessageDigest)
 fun strongEtag(value: String) = value.matches(Regex("\"[\\x21\\x23-\\x7E\\x80-\\xFF]*\""))
 private fun journal(part: File) = File(part.path + ".resume")
 private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
 private fun identity(url: String) = hex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)))
 fun load(part: File, url: String, checkActive: () -> Unit = {}): Verified? {
  if (!part.isFile || !journal(part).isFile) return null
  // Bad local metadata is a restart condition; I/O failures (including storage errors) propagate.
  val p = try { Properties().apply { journal(part).inputStream().use { load(it) } } } catch (_: IllegalArgumentException) { return null }
  val bytes = p.getProperty("bytes")?.toLongOrNull() ?: return null
  val total = p.getProperty("total")?.toLongOrNull() ?: return null
  val etag = p.getProperty("etag").orEmpty()
  if (p.getProperty("url") != identity(url) || !strongEtag(etag) || bytes <= 0 || total < bytes || part.length() < bytes) return null
  val hash = MessageDigest.getInstance("SHA-256")
  part.inputStream().use { input ->
   val buffer = ByteArray(65536); var remaining = bytes
   while (remaining > 0) {
    checkActive()
    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
    if (n < 0) return null
    hash.update(buffer, 0, n); remaining -= n
   }
  }
  if (snapshot(hash, part, bytes) != p.getProperty("sha256")) return null
  return Verified(TransferMeta(etag, total), bytes, hash)
 }
 private fun snapshot(hash: MessageDigest, part: File, bytes: Long): String {
  // Android/JVM providers normally support clone. Retain a correct fallback for other providers.
  return try { hex((hash.clone() as MessageDigest).digest()) } catch (_: CloneNotSupportedException) {
   val copy = MessageDigest.getInstance("SHA-256")
   part.inputStream().use { input ->
    val buffer = ByteArray(65536); var remaining = bytes
    while (remaining > 0) { val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); check(n > 0); copy.update(buffer, 0, n); remaining -= n }
   }
   hex(copy.digest())
  }
 }
 fun save(part: File, url: String, meta: TransferMeta, bytes: Long, hash: MessageDigest) {
  if (!strongEtag(meta.etag) || meta.total <= 0 || bytes <= 0) return
  val p = Properties().apply {
   setProperty("url", identity(url)); setProperty("etag", meta.etag); setProperty("total", meta.total.toString())
   setProperty("bytes", bytes.toString()); setProperty("sha256", snapshot(hash, part, bytes))
  }
  val tmp = File(part.path + ".resume.tmp")
  FileOutputStream(tmp).use { p.store(it, null); it.fd.sync() }
  Files.move(tmp.toPath(), journal(part).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  FileChannel.open(part.absoluteFile.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
 }
 /** Keep unvalidated/old-version bytes for explicit user cleanup rather than silently deleting them. */
 fun preserve(part: File) {
  if (part.length() > 0) {
   val suffix = ".retained-" + UUID.randomUUID()
   Files.move(part.toPath(), File(part.path + suffix).toPath())
   if (journal(part).exists()) Files.move(journal(part).toPath(), File(part.path + suffix + ".resume").toPath())
  } else Files.deleteIfExists(journal(part).toPath())
 }
 fun truncateToCheckpoint(part: File, bytes: Long) {
  RandomAccessFile(part, "rw").use { it.setLength(bytes); it.fd.sync() }
 }
}
