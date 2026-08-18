package cl.bovedawilson.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cl.bovedawilson.data.local.entity.BiometricUnlockEntity

@Dao
interface BiometricUnlockDao {
    @Query("SELECT * FROM biometric_unlock WHERE id = 1")
    suspend fun get(): BiometricUnlockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: BiometricUnlockEntity)

    @Query("DELETE FROM biometric_unlock")
    suspend fun delete()
}
