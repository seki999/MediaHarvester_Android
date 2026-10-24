package com.mediaharvester.extractor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebsiteRecoveryTest {
 @get:Rule val folder = TemporaryFolder()
 private val identity = WebsiteRecovery.identity("https://example.org/media", "v+a", "")
 @Test fun interruptedFragmentsArePreservedAndIsolated() {
  val root = folder.newFolder()
  val first = WebsiteRecovery.newAttempt(root)
  val partial = File(first,"media.mp4.part").apply { writeText("partial-fragments") }
  File(first,"media.mp4.ytdl").writeText("fragment-state")
  assertNull(WebsiteRecovery.completed(root,identity))
  val second = WebsiteRecovery.newAttempt(root)
  assertNotEquals(first,second);assertEquals("partial-fragments",partial.readText())
  assertTrue(File(first,"media.mp4.ytdl").exists());assertTrue(second.listFiles()!!.isEmpty())
 }
 @Test fun verifiedOutputCanBeReusedAfterReopening() {
  val root=folder.newFolder();val attempt=WebsiteRecovery.newAttempt(root)
  val media=File(attempt,"media.mp4").apply { writeText("verified-media-fixture") }
  WebsiteRecovery.commit(root,identity,media)
  assertEquals(media.canonicalFile,WebsiteRecovery.completed(root,identity))
  assertNull(WebsiteRecovery.completed(root,WebsiteRecovery.identity("https://example.org/media","other","")))
  assertNull(WebsiteRecovery.completed(root,WebsiteRecovery.identity("https://example.org/media","v+a","mp3")))
 }
 @Test fun damagedOrTruncatedOutputIsNeverReused() {
  val root=folder.newFolder();val attempt=WebsiteRecovery.newAttempt(root)
  val media=File(attempt,"media.mp4").apply { writeText("verified-media-fixture") }
  WebsiteRecovery.commit(root,identity,media);media.writeText("damaged")
  assertNull(WebsiteRecovery.completed(root,identity));assertTrue(media.exists())
 }
 @Test fun incompleteJournalCannotPromoteFragmentsToCompleteOutput() {
  val root=folder.newFolder();File(root,"completed.properties.tmp").writeText("unfinished-journal")
  assertNull(WebsiteRecovery.completed(root,identity))
 }
}
