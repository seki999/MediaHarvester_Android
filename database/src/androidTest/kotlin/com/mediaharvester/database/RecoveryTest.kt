package com.mediaharvester.database
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
@RunWith(AndroidJUnit4::class)
class RecoveryTest {
 @Test fun stateAndValidatorsSurviveDatabaseReopen()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.deleteDatabase("tasks.db")
  var db=TaskDatabase.open(context)
  val t=TaskEntity(url="https://example.org/a",title="a",mime="image/png",state="DOWNLOADING",bytes=42,total=100,etag="\"strong\"")
  db.tasks().save(t);db.close()
  db=TaskDatabase.open(context);db.tasks().recover()
  val result=db.tasks().get(t.id)!!
  assertEquals("INTERRUPTED",result.recovery);assertEquals("PAUSED",result.state);assertEquals(42L,result.bytes);assertEquals("\"strong\"",result.etag);db.close()
 }
 @Test fun cancelledStateRejectsLateCompletion()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val db=TaskDatabase.open(context)
  val t=TaskEntity(url="https://example.org/a",title="a",mime="image/png",state="CANCELLED");db.tasks().save(t)
  assertEquals(0,db.tasks().transition(t.id,"DOWNLOADING","COMPLETED",""));assertEquals("CANCELLED",db.tasks().get(t.id)!!.state);db.close()
 }
 @Test fun allInterruptedPhasesPauseWhileTerminalAndFailedTasksSurvive()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  context.deleteDatabase("tasks.db");var db=TaskDatabase.open(context)
  val states=listOf("QUEUED","DOWNLOADING","MERGING","VERIFYING","WAITING_NETWORK","FAILED","CANCELLED","COMPLETED")
  val tasks=states.map { state -> TaskEntity(url="https://example.org/$state",title=state,mime="video/mp4",state=state,bytes=8192,total=65536,etag="\"version\"",error=if(state=="FAILED") "storage full" else "") }
  tasks.forEach {db.tasks().save(it)};db.close();db=TaskDatabase.open(context);db.tasks().recover()
  tasks.forEach { before ->
   val after=db.tasks().get(before.id)!!
   assertEquals(if(before.state in listOf("FAILED","CANCELLED","COMPLETED")) before.state else "PAUSED",after.state)
   assertEquals(before.bytes,after.bytes);assertEquals(before.total,after.total);assertEquals(before.etag,after.etag)
  }
  db.tasks().clearHistory()
  assertNotNull(db.tasks().get(tasks.first {it.state=="FAILED"}.id))
  db.close()
 }
 @Test fun lateMetadataCannotChangeCancelledTask()=runBlocking {
  val db=TaskDatabase.open(InstrumentationRegistry.getInstrumentation().targetContext)
  val task=TaskEntity(url="https://example.org/cancel",title="cancel",mime="video/mp4",state="CANCELLED",etag="\"original\"",total=100)
  db.tasks().save(task);db.tasks().metadata(task.id,"\"late\"",999);db.tasks().progress(task.id,999,999,50)
  val result=db.tasks().get(task.id)!!;assertEquals("\"original\"",result.etag);assertEquals(100L,result.total);assertEquals(0L,result.bytes)
  db.close()
 }

}
