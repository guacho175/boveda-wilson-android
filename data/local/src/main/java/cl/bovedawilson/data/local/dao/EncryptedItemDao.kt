package cl.bovedawilson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import kotlinx.coroutines.flow.Flow

data class BackupSizeStats(
    val itemCount: Long,
    val ciphertextBytes: Long,
    val maxCiphertextBytes: Long,
)

// Un DAO es una superficie de consultas, no una clase con demasiadas responsabilidades:
// cada método es una sentencia SQL distinta sobre la misma tabla. Mismo criterio que en
// EncryptedItemStore, que ya lleva esta supresión.
@Suppress("TooManyFunctions")
@Dao
interface EncryptedItemDao {
    @Query("SELECT * FROM encrypted_items WHERE tombstone = 0 ORDER BY updatedAt DESC")
    fun observeAllActive(): Flow<List<EncryptedItemEntity>>

    @Query("SELECT * FROM encrypted_items WHERE tombstone = 0 ORDER BY updatedAt DESC")
    suspend fun getAllActive(): List<EncryptedItemEntity>

    @Query("SELECT * FROM encrypted_items ORDER BY updatedAt DESC")
    suspend fun getAllItems(): List<EncryptedItemEntity>

    @Query(
        "SELECT COUNT(*) AS itemCount, " +
            "COALESCE(SUM(LENGTH(ciphertext)), 0) AS ciphertextBytes, " +
            "COALESCE(MAX(LENGTH(ciphertext)), 0) AS maxCiphertextBytes FROM encrypted_items"
    )
    suspend fun getBackupSizeStats(): BackupSizeStats

    @Query("SELECT COUNT(*) FROM encrypted_items WHERE tombstone = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM encrypted_items WHERE itemId = :itemId")
    suspend fun getById(itemId: String): EncryptedItemEntity?

    @Query("SELECT * FROM encrypted_items WHERE itemId = :itemId")
    fun observeById(itemId: String): Flow<EncryptedItemEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(item: EncryptedItemEntity)

    @Update
    suspend fun update(item: EncryptedItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(items: List<EncryptedItemEntity>)

    @Query("DELETE FROM encrypted_items")
    suspend fun deleteAll()

    // Métodos de sincronización (Fase 5)
    @Query("SELECT * FROM encrypted_items WHERE dirty = 1")
    suspend fun getAllDirtyItems(): List<EncryptedItemEntity>

    @Query(
        """
        UPDATE encrypted_items
        SET dirty = 0, lastSyncedRevision = :uploadedRevision
        WHERE itemId = :itemId AND revision = :uploadedRevision AND dirty = 1
        """
    )
    suspend fun markPushSucceeded(itemId: String, uploadedRevision: Int): Int

    @Query("SELECT MAX(lastSyncedRevision) FROM encrypted_items WHERE itemId = :itemId")
    suspend fun getMaxAcceptedRevisionForItem(itemId: String): Int?
}
