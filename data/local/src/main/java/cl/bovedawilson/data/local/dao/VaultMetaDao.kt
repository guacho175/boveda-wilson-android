package cl.bovedawilson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultMetaDao {
    @Query("SELECT * FROM vault_meta LIMIT 1")
    suspend fun getMeta(): VaultMetaEntity?

    @Query("SELECT * FROM vault_meta LIMIT 1")
    fun observeMeta(): Flow<VaultMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(meta: VaultMetaEntity)

    @Transaction
    suspend fun replaceIfUnchanged(expected: VaultMetaEntity, replacement: VaultMetaEntity): Boolean {
        if (getMeta() != expected) return false
        insertOrUpdate(replacement)
        return true
    }

    @Query("DELETE FROM vault_meta")
    suspend fun deleteAll()
}
