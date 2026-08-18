package cl.bovedawilson.data.local.store

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext

data class ItemLocalMetadata(
    val cryptoVersion: Int,
    val schemaVersion: Int,
    val revision: Int,
    val tombstone: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val dirty: Boolean,
    val lastSyncedRevision: Int,
    val conflictOf: String?
)

/** Un registro cifrado tal como sale del almacén: identificador, ciphertext opaco y
 * metadatos no sensibles. Nunca contiene contenido descifrado. */
data class StoredItem(
    val itemId: String,
    val ciphertext: Ciphertext,
    val metadata: ItemLocalMetadata
)

@Suppress("TooManyFunctions")
interface EncryptedItemStore {
    suspend fun put(itemId: String, ciphertext: Ciphertext, metadata: ItemLocalMetadata)
    suspend fun get(itemId: String): Pair<Ciphertext, ItemLocalMetadata>?
    suspend fun delete(itemId: String)

    /** Todos los registros vivos (sin tombstone), más recientes primero. */
    suspend fun listActive(): List<StoredItem>

    // Métodos de sincronización (Fase 5)
    /** Snapshot coherente de cada registro dirty obtenido por una única consulta Room. */
    suspend fun getDirtySnapshots(): List<StoredItem>
    suspend fun getLastPullAt(): Long?
    suspend fun updateLastPullAt(timestamp: Long)
    suspend fun getMaxAcceptedRevision(itemId: String): Int

    /**
     * Confirma una subida solo si el registro sigue en la revisión que se subió.
     * Devuelve `false` cuando una edición concurrente hizo obsoleto el snapshot.
     */
    suspend fun markPushSucceeded(itemId: String, uploadedRevision: Int): Boolean

    // Métodos de resolución de conflictos (Fase 5.4)
    suspend fun stageConflict(
        itemId: String,
        remoteItem: RemoteConflictItem,
        detectedAt: Long
    )

    suspend fun getPendingConflicts(): List<PendingConflict>

    suspend fun resolveAndInsertConflictCopy(data: ConflictResolutionData)

    suspend fun clearConflictStaging(itemId: String)
}

data class RemoteConflictItem(
    val ciphertext: ByteArray,
    val cryptoVersion: Int,
    val schemaVersion: Int,
    val revision: Int,
    val tombstone: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RemoteConflictItem
        return ciphertext.contentEquals(other.ciphertext) &&
            cryptoVersion == other.cryptoVersion &&
            schemaVersion == other.schemaVersion &&
            revision == other.revision &&
            tombstone == other.tombstone &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + cryptoVersion
        result = 31 * result + schemaVersion
        result = 31 * result + revision
        result = 31 * result + tombstone.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

data class ConflictResolutionData(
    val originalItemId: String,
    val newItemId: String,
    val localCiphertext: Ciphertext,
    val localMetadata: ItemLocalMetadata,
    val remoteCiphertext: Ciphertext,
    val remoteMetadata: RemoteConflictItem
)

data class PendingConflict(
    val itemId: String,
    val detectedAt: Long,
    val remoteRevision: Int
)
