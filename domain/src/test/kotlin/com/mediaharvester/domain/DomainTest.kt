package com.mediaharvester.domain
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DomainTest {
 @Test fun urlsFromMultilineAndShareText() { assertEquals(listOf("https://example.org/video", "http://example.org/a"), Urls.parse("Watch https://example.org/video\nhttp://example.org/a https://example.org/video")) }
 @Test fun rejectsUnsafeSchemesCredentialsAndControlCharacters() {
  listOf("file:///etc/passwd", "ftp://example.org/a", "https://user:secret@example.org", "https://example.org/\nx", "https://example.org:99999/a", "invalid").forEach { url -> assertTrue(url,runCatching { Urls.validate(url) }.isFailure) }
 }
 @Test fun stateMachineProtectsCompletionAndCancellation() {
  assertFalse(StateMachine.allows(TaskState.CANCELLED,TaskState.COMPLETED))
  assertFalse(StateMachine.allows(TaskState.DOWNLOADING,TaskState.COMPLETED))
  assertTrue(StateMachine.allows(TaskState.VERIFYING,TaskState.SAVING))
  assertTrue(StateMachine.allows(TaskState.SAVING,TaskState.COMPLETED))
 }
 @Test fun crashRecoveryDoesNotAutoStart() {
  TaskState.entries.filter { it !in listOf(TaskState.COMPLETED,TaskState.FAILED,TaskState.CANCELLED,TaskState.PAUSED) }.forEach { assertEquals(TaskState.PAUSED,StateMachine.recover(it)) }
 }
 @Test fun safeNamesPreventTraversal() { assertFalse(safeName("../../a:b\u0000").any {it=='/' || it==':' || it=='\u0000'}); assertEquals("media",safeName("...")) }
 @Test fun fileCorruptionChangesDigest() { val file=File.createTempFile("hash",".bin");try {file.writeText("correct");val digest=sha256(file);file.appendText("wrong");assertNotEquals(digest,sha256(file))}finally{file.delete()} }
}
