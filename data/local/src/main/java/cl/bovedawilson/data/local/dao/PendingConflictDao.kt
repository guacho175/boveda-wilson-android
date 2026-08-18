package cl.bovedawilson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cl.bovedawilson.data.local.entity.PendingConflictEntity

@Dao
interface PendingConflictDao {
    @Query("SELECT * FROM pending_conflicts")
    suspend fun getAll(): List<PendingConflictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(conflict: PendingConflictEntity)

    @Query("DELETE FROM pending_conflicts WHERE itemId = :itemId")
    suspend fun deleteById(itemId: String)

    @Query("DELETE FROM pending_conflicts")
    suspend fun deleteAll()
}
