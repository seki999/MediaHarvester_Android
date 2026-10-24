package com.mediaharvester.extractor

import com.mediaharvester.domain.sha256
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineFilesTest {
 @get:Rule val folder=TemporaryFolder()
 private fun hash(bytes: ByteArray): String=folder.newFile().apply { writeBytes(bytes) }.let { sha256(it) }
 @Test fun upgradesOldEngineAndPreservesDownloadDirectory() {
  val dir=folder.newFolder();val task=folder.newFile().apply { writeText("retained part") }
  val old="old engine".toByteArray();EngineFiles.install(dir,old,"2025.11.12",hash(old))
  val fresh="new engine including EJS".toByteArray()
  EngineFiles.install(dir,fresh,EngineFiles.VERSION,hash(fresh))
  assertEquals(EngineFiles.VERSION,EngineFiles.current(dir));assertArrayEquals(fresh,File(dir,"yt-dlp").readBytes())
  assertEquals("retained part",task.readText())
 }
 @Test fun corruptedUpdateNeverReplacesInstalledEngine() {
  val dir=folder.newFolder();val original="good engine".toByteArray()
  EngineFiles.install(dir,original,EngineFiles.VERSION,hash(original))
  assertTrue(runCatching { EngineFiles.install(dir,"broken".toByteArray(),EngineFiles.VERSION,hash(original)) }.isFailure)
  assertEquals(EngineFiles.VERSION,EngineFiles.current(dir));assertArrayEquals(original,File(dir,"yt-dlp").readBytes())
 }
 @Test fun incompatibleRuntimeRollsBackToWorkingEngine() {
  val dir=folder.newFolder();val previous="working".toByteArray();val future="requires newer python".toByteArray()
  EngineFiles.install(dir,previous,EngineFiles.VERSION,hash(previous))
  assertTrue(runCatching { EngineFiles.update(dir,future,"2026.11.01",hash(future)) { error("Cannot start engine") } }.isFailure)
  assertEquals(EngineFiles.VERSION,EngineFiles.current(dir));assertArrayEquals(previous,File(dir,"yt-dlp").readBytes())
 }
 @Test fun detectsInterruptedOrModifiedEngineOnNextStartup() {
  val dir=folder.newFolder();val bytes="engine".toByteArray()
  EngineFiles.install(dir,bytes,EngineFiles.VERSION,hash(bytes))
  File(dir,"yt-dlp").writeText("truncated")
  assertNull(EngineFiles.current(dir))
 }
}
