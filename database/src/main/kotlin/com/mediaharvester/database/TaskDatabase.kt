package com.mediaharvester.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mediaharvester.domain.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "tasks", indices = [Index("state"), Index("digest")])
data class TaskEntity(
 @PrimaryKey val id: String = UUID.randomUUID().toString(),
 val url: String, val title: String, val mime: String,
 val website: Boolean = false, val selector: String = "", val conversion: String = "",
 val state: String = TaskState.QUEUED.name, val bytes: Long = 0, val total: Long = 0,
 val speed: Long = 0, val etag: String = "", val error: String = "", val attempts: Int = 0,
 val outputUri: String = "", val digest: String = "", val destination: String = "", val gallery: Boolean = false,
 @ColumnInfo(defaultValue = "''") val recovery: String = "",
 val created: Long = System.currentTimeMillis()
)
@Entity(tableName = "recent")
data class RecentEntity(@PrimaryKey val url: String, val time: Long = System.currentTimeMillis())
@Dao
interface TaskDao {
 @Query("SELECT * FROM tasks ORDER BY created DESC") fun observe(): Flow<List<TaskEntity>>
 @Query("SELECT * FROM tasks ORDER BY created") suspend fun all(): List<TaskEntity>
 @Query("SELECT * FROM tasks WHERE id=:id") suspend fun get(id: String): TaskEntity?
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(task: TaskEntity)
 @Query("UPDATE tasks SET state=:next, error=:error, speed=0 WHERE id=:id AND state=:expected") suspend fun transition(id: String, expected: String, next: String, error: String): Int
 @Query("UPDATE tasks SET outputUri=:uri,digest=:digest,mime=:mime WHERE id=:id AND state='SAVING'") suspend fun journal(id: String,uri: String,digest: String,mime: String): Int
 @Query("UPDATE tasks SET state='COMPLETED',outputUri=:uri,digest=:digest,bytes=:size,total=:size,speed=0,error='',recovery='' WHERE id=:id AND state='SAVING'") suspend fun complete(id: String,uri: String,digest: String,size: Long): Int
 @Query("UPDATE tasks SET outputUri='',digest='' WHERE id=:id AND state!='COMPLETED'") suspend fun clearOutput(id: String)
 @Query("UPDATE tasks SET bytes=:bytes,total=:total,speed=:speed WHERE id=:id AND state='DOWNLOADING'") suspend fun progress(id: String, bytes: Long, total: Long, speed: Long)
 @Query("UPDATE tasks SET etag=:etag,total=:total WHERE id=:id AND state='DOWNLOADING'") suspend fun metadata(id: String, etag: String, total: Long)
 @Query("UPDATE tasks SET recovery=:recovery WHERE id=:id AND state NOT IN ('COMPLETED','CANCELLED')") suspend fun recovery(id: String, recovery: String)
 @Query("UPDATE tasks SET bytes=0,total=0,speed=0,etag='',recovery='DATA_CLEARED' WHERE state NOT IN ('COMPLETED','CANCELLED')") suspend fun resetTransfers()
 @Query("UPDATE tasks SET state='PAUSED',speed=0,recovery='INTERRUPTED' WHERE state IN ('QUEUED','DOWNLOADING','MERGING','VERIFYING','WAITING_NETWORK')") suspend fun recover()
 @Query("SELECT * FROM tasks WHERE digest=:digest AND state='COMPLETED' LIMIT 1") suspend fun duplicate(digest: String): TaskEntity?
 @Query("DELETE FROM tasks WHERE state IN ('COMPLETED','CANCELLED')") suspend fun clearHistory()
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun recent(item: RecentEntity)
 @Query("SELECT * FROM recent ORDER BY time DESC LIMIT 10") fun recent(): Flow<List<RecentEntity>>
 @Query("DELETE FROM recent") suspend fun clearRecent()
}
@Database(entities = [TaskEntity::class, RecentEntity::class], version = 2, exportSchema = true)
abstract class TaskDatabase: RoomDatabase() {
 abstract fun tasks(): TaskDao
 companion object {
  val MIGRATION_1_2 = object: Migration(1, 2) {
   override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE tasks ADD COLUMN recovery TEXT NOT NULL DEFAULT ''") }
  }
  fun open(context: Context) = Room.databaseBuilder(context, TaskDatabase::class.java, "tasks.db")
   .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).addCallback(object: Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
   }).build()
 }
}
