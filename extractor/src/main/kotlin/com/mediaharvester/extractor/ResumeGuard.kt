package com.mediaharvester.extractor

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Fail closed on changed stream identity. Quarantine is retained until explicit deletion. */
internal object ResumeGuard {
 fun prepare(directory: File, identity: String) {
  val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
  val marker = File(directory, ".resume-identity")
  val payload = directory.listFiles().orEmpty().filter { !it.name.startsWith(".") }
  if(payload.isNotEmpty() && (!marker.isFile || marker.readText() != digest)) {
   val quarantine = File(directory, ".incompatible-${UUID.randomUUID()}").apply { check(mkdirs()) }
   payload.forEach { check(it.renameTo(File(quarantine, it.name))) { "Cannot isolate incompatible download; resume stopped" } }
  }
  val pending = File(directory, ".resume-identity.tmp")
  pending.outputStream().use { it.write(digest.toByteArray()); it.fd.sync() }
  check(pending.renameTo(marker)) { "Cannot persist resume identity" }
 }
}
