package com.mediaharvester.extractor

import java.io.File
import com.mediaharvester.domain.sha256

/** Script replacement is atomic; a failed download/checksum never overwrites the engine. */
internal object EngineFiles {
 const val VERSION = "2026.10.21"
 const val SHA256 = "b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14"
 fun current(directory: File): String? {
  val fields=File(directory,".engine-version").takeIf { it.isFile }?.readText()?.split(' ') ?: return null
  if(fields.size!=2 || !fields[0].matches(Regex("20\\d{2}\\.\\d{2}\\.\\d{2}"))) return null
  val engine=File(directory,"yt-dlp")
  return fields[0].takeIf { engine.isFile && sha256(engine)==fields[1] }
 }
 fun update(directory: File, bytes: ByteArray, version: String, checksum: String, validate: () -> Unit) {
  val previousVersion=current(directory) ?: error("Installed engine is invalid")
  val previous=File(directory,"yt-dlp").readBytes()
  val previousChecksum=sha256(File(directory,"yt-dlp"))
  try {
   install(directory,bytes,version,checksum)
   validate()
  } catch(e: Exception) {
   install(directory,previous,previousVersion,previousChecksum)
   throw e
  }
 }
 fun install(directory: File, bytes: ByteArray, version: String, checksum: String) {
  require(version.matches(Regex("20\\d{2}\\.\\d{2}\\.\\d{2}"))) { "Invalid engine version" }
  directory.mkdirs()
  val temporary=File(directory,".engine-download")
  try {
   temporary.outputStream().use { it.write(bytes); it.fd.sync() }
   require(sha256(temporary)==checksum) { "Engine checksum mismatch; installed engine unchanged" }
   check(temporary.renameTo(File(directory,"yt-dlp"))) { "Cannot install engine" }
   val marker=File(directory,".engine-version.tmp")
   marker.outputStream().use { it.write("$version $checksum".toByteArray()); it.fd.sync() }
   check(marker.renameTo(File(directory,".engine-version")))
  } finally { temporary.delete() }
 }
}
