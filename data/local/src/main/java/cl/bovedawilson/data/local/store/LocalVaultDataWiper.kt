package cl.bovedawilson.data.local.store

import androidx.room.withTransaction
import cl.bovedawilson.data.local.db.VaultDatabase

/**
 * Borra atomically todo el estado Room de la unica boveda local.
 *
 * No invalida claves de Android Keystore ni modifica DataStore: esos recursos viven
 * fuera de la transaccion SQLite y el orquestador debe limpiarlos despues del commit.
 */
interface LocalVaultDataWiper {
    suspend fun wipeAllVaultData()
}

class RoomLocalVaultDataWiper(
    private val database: VaultDatabase
) : LocalVaultDataWiper {
    override suspend fun wipeAllVaultData() {
        database.withTransaction {
            database.pendingConflictDao().deleteAll()
            database.encryptedItemDao().deleteAll()
            database.syncStateDao().delete()
            database.biometricUnlockDao().delete()
            database.vaultMetaDao().deleteAll()
        }
    }
}
