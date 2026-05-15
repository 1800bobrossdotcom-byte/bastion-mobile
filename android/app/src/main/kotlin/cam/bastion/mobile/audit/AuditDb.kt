package cam.bastion.mobile.audit

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "audit")
data class AuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val host: String,
    val action: String, // "BLOCKED"
)

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: AuditEvent)

    @Query("SELECT * FROM audit ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<AuditEvent>>

    @Query("SELECT COUNT(*) FROM audit")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM audit")
    suspend fun clear()

    @Query("DELETE FROM audit WHERE ts < :cutoff")
    suspend fun trimOlderThan(cutoff: Long)
}

@Database(entities = [AuditEvent::class], version = 1, exportSchema = false)
abstract class AuditDb : RoomDatabase() {
    abstract fun dao(): AuditDao

    companion object {
        @Volatile private var INSTANCE: AuditDb? = null
        fun get(ctx: android.content.Context): AuditDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext, AuditDb::class.java, "bastion-audit.db"
                ).build().also { INSTANCE = it }
            }
    }
}
