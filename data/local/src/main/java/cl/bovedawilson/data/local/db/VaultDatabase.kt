package cl.bovedawilson.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import cl.bovedawilson.data.local.dao.BiometricUnlockDao
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.dao.PendingConflictDao
import cl.bovedawilson.data.local.dao.SyncStateDao
import cl.bovedawilson.data.local.dao.VaultMetaDao
import cl.bovedawilson.data.local.entity.BiometricUnlockEntity
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.PendingConflictEntity
import cl.bovedawilson.data.local.entity.SyncStateEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity

@Database(
    entities = [
        VaultMetaEntity::class,
        EncryptedItemEntity::class,
        PendingConflictEntity::class,
        BiometricUnlockEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultMetaDao(): VaultMetaDao
    abstract fun encryptedItemDao(): EncryptedItemDao
    abstract fun pendingConflictDao(): PendingConflictDao
    abstract fun biometricUnlockDao(): BiometricUnlockDao
    abstract fun syncStateDao(): SyncStateDao
}
