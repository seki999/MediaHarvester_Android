package com.mediaharvester.extractor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResumeGuardTest {
 @get:Rule val folder = TemporaryFolder()
 @Test fun pauseAndProcessRestartRetainMatchingPartAndJournal() {
  val dir=folder.newFolder(); ResumeGuard.prepare(dir,"manifest A")
  File(dir,"media.part").writeText("valid bytes"); File(dir,"media.ytdl").writeText("journal")
  ResumeGuard.prepare(dir,"manifest A")
  assertEquals("valid bytes",File(dir,"media.part").readText())
  assertEquals("journal",File(dir,"media.ytdl").readText())
 }
 @Test fun changedFragmentManifestIsIsolatedWithoutDeletingBytes() {
  val dir=folder.newFolder(); ResumeGuard.prepare(dir,"manifest A")
  File(dir,"media.part").writeText("old bytes")
  ResumeGuard.prepare(dir,"manifest B")
  assertFalse(File(dir,"media.part").exists())
  val isolated=dir.listFiles()!!.single { it.isDirectory }
  assertEquals("old bytes",File(isolated,"media.part").readText())
 }
 @Test fun legacyUnvalidatedFragmentsCannotBeAppended() {
  val dir=folder.newFolder();File(dir,"media.part").writeText("untrusted")
  ResumeGuard.prepare(dir,"manifest A")
  assertFalse(File(dir,"media.part").exists())
 }
}
