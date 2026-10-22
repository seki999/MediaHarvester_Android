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
  assertEquals("PAUSED",result.state);assertEquals(42L,result.bytes);assertEquals("\"strong\"",result.etag);db.close()
 }
 @Test fun cancelledStateRejectsLateCompletion()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val db=TaskDatabase.open(context)
  val t=TaskEntity(url="https://example.org/a",title="a",mime="image/png",state="CANCELLED");db.tasks().save(t)
  assertEquals(0,db.tasks().transition(t.id,"DOWNLOADING","COMPLETED",""));assertEquals("CANCELLED",db.tasks().get(t.id)!!.state);db.close()
 }
}
