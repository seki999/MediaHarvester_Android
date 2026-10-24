package com.mediaharvester.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
 @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TaskDatabase::class.java)
 @Test fun upgradePreservesPartialTransferAndPublicationJournal() {
  helper.createDatabase("migration-recovery",1).apply {
   execSQL("INSERT INTO tasks VALUES ('partial','https://example.org/file','file','video/mp4',0,'','','DOWNLOADING',8192,65536,100,'\"v1\"','',0,'','','',0,1)")
   execSQL("INSERT INTO tasks VALUES ('saving','https://example.org/file','file','video/mp4',0,'','','SAVING',65536,65536,0,'\"v1\"','',0,'content://media/owned','digest','',0,2)")
   close()
  }
  helper.runMigrationsAndValidate("migration-recovery",2,true,TaskDatabase.MIGRATION_1_2).use { db ->
   db.query("SELECT bytes,etag,recovery FROM tasks WHERE id='partial'").use { c ->
    assertTrue(c.moveToFirst());assertEquals(8192L,c.getLong(0));assertEquals("\"v1\"",c.getString(1));assertEquals("",c.getString(2))
   }
   db.query("SELECT state,outputUri,digest FROM tasks WHERE id='saving'").use { c ->
    assertTrue(c.moveToFirst());assertEquals("SAVING",c.getString(0));assertEquals("content://media/owned",c.getString(1));assertEquals("digest",c.getString(2))
   }
  }
 }
}
