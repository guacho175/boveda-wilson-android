package cl.bovedawilson.data.remote.firestore

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext

/**
 * DTO para un ítem remoto durante operaciones de sincronización.
 * Contiene solo ciphertext opaco y metadatos mínimos.
 */
data class RemoteItemData(
    val id: String,
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
        other as RemoteItemData
        return id == other.id &&
            ciphertext.contentEquals(other.ciphertext) &&
            cryptoVersion == other.cryptoVersion &&
            schemaVersion == other.schemaVersion &&
            revision == other.revision &&
            tombstone == other.tombstone &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + cryptoVersion
        result = 31 * result + schemaVersion
        result = 31 * result + revision
        result = 31 * result + tombstone.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

data class RemoteVaultMetadata(
    val schemaVersion: Int,
    val cryptoVersion: Int,
    val kdfName: String,
    val kdfMemoryKib: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    val kdfOutputLen: Int,
    val passwordSalt: ByteArray,
    val passwordWrappedVdek: ByteArray,
    val recoverySalt: ByteArray,
    val recoveryWrappedVdek: ByteArray,
    val passwordWrapEpoch: Int,
    val recoveryWrapEpoch: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val metaRevision: Int
)

data class RemoteItemMetadata(
    val cryptoVersion: Int,
    val schemaVersion: Int,
    val revision: Int,
    val tombstone: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class RemoteVaultData(val id: String, val metadata: RemoteVaultMetadata)

// La interfaz mantiene un único límite de confianza: toda operación remota de la bóveda cifrada.
// Dividirla solo para satisfacer el umbral ocultaría ese contrato sin reducir responsabilidades.
@Suppress("TooManyFunctions")
interface FirestoreVaultSource {
    /** Crea la metadata o acepta un reintento byte-a-byte idéntico. Nunca actualiza. */
    suspend fun createVaultMeta(expectedUid: String, vaultId: String, metadata: RemoteVaultMetadata)

    /** Actualiza con el contrato monotónico o acepta un reintento idéntico. Nunca crea. */
    suspend fun updateVaultMeta(expectedUid: String, vaultId: String, metadata: RemoteVaultMetadata)
    suspend fun replaceVaultMetaIfUnchanged(
        expectedUid: String,
        vaultId: String,
        expected: RemoteVaultMetadata,
        replacement: RemoteVaultMetadata,
    ): Boolean = false
    suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata?
    suspend fun listVaults(expectedUid: String): List<RemoteVaultData>

    suspend fun uploadItem(
        expectedUid: String,
        vaultId: String,
        itemId: String,
        ciphertext: Ciphertext,
        metadata: RemoteItemMetadata
    )
    suspend fun createItemIfAbsentOrIdentical(
        expectedUid: String,
        vaultId: String,
        item: RemoteItemData,
    ): Boolean = false

    /** Reemplaza el item solo si el estado remoto sigue siendo exactamente el observado. */
    suspend fun replaceItemIfUnchanged(
        expectedUid: String,
        vaultId: String,
        expected: RemoteItemData?,
        replacement: RemoteItemData,
    ): Boolean = false

    suspend fun getItem(
        expectedUid: String,
        vaultId: String,
        itemId: String
    ): Pair<Ciphertext, RemoteItemMetadata>?

    /** Escaneo autoritativo desde servidor; la reconciliación usa revision, no reloj cliente. */
    suspend fun listItems(expectedUid: String, vaultId: String): List<RemoteItemData>

    /** Marcadores terminales que deben reanudarse antes de adoptar una bóveda. */
    suspend fun listDeletedVaultIds(expectedUid: String): Set<String>

    /**
     * Inicia una eliminacion terminal e idempotente, purga los items y elimina la metadata.
     * El marcador remoto minimo permanece para impedir que otro cliente recree la boveda.
     */
    suspend fun purgeVault(expectedUid: String, vaultId: String, deletedAt: Long)
}
