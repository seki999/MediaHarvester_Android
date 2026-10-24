package com.mediaharvester.extractor

import com.mediaharvester.domain.sha256
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** Partial fragments have no durable remote validator. Isolate attempts; only reuse verified final output. */
object WebsiteRecovery {
 val downloadOptions = listOf("--continue", "--part", "--abort-on-unavailable-fragments", "--hls-prefer-native")
 fun identity(url: String, selector: String, conversion: String): String {
  val bytes = listOf(url, selector, conversion).joinToString("\u0000").toByteArray(Charsets.UTF_8)
  return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
 }
 fun newAttempt(root: File): File = File(root, "attempt-${UUID.randomUUID()}").apply { check(mkdirs()) { "Cannot create download directory" } }
 fun completed(root: File, identity: String, checkActive: () -> Unit = {}): File? {
  val manifest = File(root, "completed.properties")
  if (!manifest.isFile) return null
  val p = try { Properties().apply { manifest.inputStream().use { load(it) } } } catch (_: IllegalArgumentException) { return null }
  if (p.getProperty("identity") != identity) return null
  val relative = p.getProperty("file") ?: return null
  val file = File(root, relative).canonicalFile
  if (!file.toPath().startsWith(root.canonicalFile.toPath()) || !file.isFile || file.length() <= 0) return null
  if (file.length().toString() != p.getProperty("bytes") || sha256(file,checkActive) != p.getProperty("sha256")) return null
  return file
 }
 fun commit(root: File, identity: String, output: File, checkActive: () -> Unit = {}) {
  // Order: native process exited, media verified, source synced, journal synced and atomically renamed.
  FileOutputStream(output, true).use { it.fd.sync() }
  FileChannel.open(output.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
  val p = Properties().apply {
   setProperty("identity", identity); setProperty("file", output.relativeTo(root).path)
   setProperty("bytes", output.length().toString()); setProperty("sha256", sha256(output,checkActive))
  }
  val temporary = File(root, "completed.properties.tmp")
  FileOutputStream(temporary).use { p.store(it, null); it.fd.sync() }
  Files.move(temporary.toPath(), File(root, "completed.properties").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
 }
}
