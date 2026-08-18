package cl.bovedawilson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cl.bovedawilson.data.local.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun delete()

    @Query("SELECT lastPullAt FROM sync_state WHERE id = 1")
    suspend fun getLastPullAt(): Long?

    @Query("UPDATE sync_state SET lastPullAt = :timestamp WHERE id = 1")
    suspend fun updateLastPullAt(timestamp: Long)
}
