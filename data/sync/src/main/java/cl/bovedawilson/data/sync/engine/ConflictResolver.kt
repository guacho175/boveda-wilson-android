package cl.bovedawilson.data.sync.engine

import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.mapper.CiphertextMapper
import cl.bovedawilson.data.local.store.ConflictResolutionData
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.local.store.ItemLocalMetadata
import cl.bovedawilson.data.local.store.RemoteConflictItem
import cl.bovedawilson.data.local.store.StoredItem
import cl.bovedawilson.data.sync.dto.RemoteItemDto
import java.util.UUID

/**
 * Resuelve conflictos de sincronización creando copias locales sin perder datos.
 *
 * Cuando se detecta un conflicto (remoto más nuevo que `lastSyncedRevision` pero local está dirty):
 * 1. Si sesión desbloqueada: resolver inmediatamente
 *    - Aceptar remoto como oficial
 *    - Crear copia de local con itemId nuevo
 *    - Recifrar la copia (AAD incluye itemId)
 *    - Marcar con conflictOf = originalItemId
 * 2. Si sesión bloqueada: stagear el DTO remoto
 *    - Guardar en pendingRemote*
 *    - Conservar ciphertext local intacto
 *    - Al desbloquear, resolver desde staging sin otra lectura de red
 *
 * Implementa `docs/sync-protocol.md` §5.
 */
class ConflictResolver(
    private val localStore: EncryptedItemStore,
    private val itemDao: EncryptedItemDao
) {
    /**
     * Resuelve un conflicto detectado durante sincronización.
     *
     * Si la sesión está desbloqueada, recifra la copia local con su nuevo itemId.
     * Si está bloqueada, stagea el DTO remoto para resolverlo después.
     */
    suspend fun resolveConflict(
        remoteItem: RemoteItemDto,
        localSnapshot: StoredItem,
        detectedAt: Long,
        expectedVaultId: String
    ) {
        check(expectedVaultId.isNotBlank())
        // El worker nunca descifra: incluso con la UI abierta, deja el remoto en staging.
        // La recifra ocurre únicamente dentro del lease de un desbloqueo en primer plano.
        stageForLaterResolution(
            originalItemId = localSnapshot.itemId,
            remoteItem = remoteItem,
            detectedAt = detectedAt
        )
    }

    /**
     * Resuelve todos los DTO remotos ya guardados en staging. No consulta Firestore:
     * cada conflicto se reconstruye exclusivamente desde la transacción local previa.
     */
    suspend fun resolveAllPending(
        vault: UnlockedVault,
        vaultId: String,
        leaseStillValid: () -> Boolean
    ) {
        localStore.getPendingConflicts().forEach { pending ->
            requireLease(leaseStillValid)
            resolveFromStaging(pending.itemId, vault, vaultId, leaseStillValid)
        }
    }

    /**
     * Resuelve un conflicto almacenado en staging cuando la sesión se desbloquea.
     */
    suspend fun resolveFromStaging(
        itemId: String,
        vault: UnlockedVault,
        vaultId: String,
        leaseStillValid: () -> Boolean
    ) {
        requireLease(leaseStillValid)
        val localItem = checkNotNull(localStore.get(itemId)) {
            "Pending conflict data is unavailable"
        }
        val (localCiphertext, localMetadata) = localItem

        val remoteItem = loadStagedRemote(itemId)

        // Generar nuevo itemId para la copia
        val newItemId = UUID.randomUUID().toString()

        // Recifrar la copia con el nuevo itemId
        val remoteCiphertext = CiphertextMapper.fromPersistedBytes(
            if (remoteItem.tombstone) byteArrayOf() else remoteItem.ciphertext
        )
        val recipheredCiphertext = recipherConflictCopy(
            originalItemId = itemId,
            newItemId = newItemId,
            localCiphertext = localCiphertext,
            localMetadata = localMetadata,
            vaultId = vaultId,
            vault = vault
        )

        // Insertar remoto como oficial y crear copia
        requireLease(leaseStillValid)
        localStore.resolveAndInsertConflictCopy(
            ConflictResolutionData(
                originalItemId = itemId,
                newItemId = newItemId,
                localCiphertext = recipheredCiphertext,
                localMetadata = localMetadata.copy(
                    revision = localMetadata.revision,
                    dirty = true,
                    lastSyncedRevision = 0,
                    conflictOf = null
                ),
                remoteCiphertext = remoteCiphertext,
                remoteMetadata = remoteItem
            )
        )
    }

    private suspend fun loadStagedRemote(itemId: String): RemoteConflictItem {
        val entity = checkNotNull(itemDao.getById(itemId)) {
            "Pending conflict data is unavailable"
        }
        val ciphertext = entity.pendingRemoteCiphertext
        val cryptoVersion = entity.pendingRemoteCryptoVersion
        val schemaVersion = entity.pendingRemoteSchemaVersion
        val revision = entity.pendingRemoteRevision
        val tombstone = entity.pendingRemoteTombstone
        val createdAt = entity.pendingRemoteCreatedAt
        val updatedAt = entity.pendingRemoteUpdatedAt
        check(
            ciphertext != null && revision != null && cryptoVersion != null &&
                schemaVersion != null && tombstone != null && createdAt != null && updatedAt != null
        ) { "Pending conflict data is incomplete" }
        return RemoteConflictItem(
            ciphertext = ciphertext,
            cryptoVersion = cryptoVersion,
            schemaVersion = schemaVersion,
            revision = revision,
            tombstone = tombstone,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private suspend fun stageForLaterResolution(
        originalItemId: String,
        remoteItem: RemoteItemDto,
        detectedAt: Long
    ) {
        val remoteConflictItem = RemoteConflictItem(
            ciphertext = remoteItem.ciphertext,
            cryptoVersion = remoteItem.cryptoVersion,
            schemaVersion = remoteItem.schemaVersion,
            revision = remoteItem.revision,
            tombstone = remoteItem.tombstone,
            createdAt = remoteItem.createdAt,
            updatedAt = remoteItem.updatedAt
        )

        localStore.stageConflict(
            itemId = originalItemId,
            remoteItem = remoteConflictItem,
            detectedAt = detectedAt
        )
    }

    @Suppress("LongParameterList")
    private fun recipherConflictCopy(
        originalItemId: String,
        newItemId: String,
        localCiphertext: Ciphertext,
        localMetadata: ItemLocalMetadata,
        vaultId: String,
        vault: UnlockedVault
    ): Ciphertext {
        if (localMetadata.tombstone) return CiphertextMapper.fromPersistedBytes(byteArrayOf())

        // Descifrar con el AAD original (itemId original)
        val originalAad = AadBuilder.forItem(
            vaultId = vaultId,
            itemId = originalItemId,
            schemaVersion = SchemaVersion(localMetadata.schemaVersion),
            cryptoVersion = CryptoVersion(localMetadata.cryptoVersion)
        )
        val plaintext = vault.decrypt(localCiphertext, originalAad)

        // Recifrar con el AAD nuevo (itemId nuevo)
        val newAad = AadBuilder.forItem(
            vaultId = vaultId,
            itemId = newItemId,
            schemaVersion = SchemaVersion(localMetadata.schemaVersion),
            cryptoVersion = CryptoVersion(localMetadata.cryptoVersion)
        )
        // ItemPayload contiene Strings administrados por la JVM: no existe un borrado en
        // sitio honesto. La referencia queda limitada a este bloque y nunca se persiste.
        return vault.encrypt(plaintext, newAad)
    }

    private fun requireLease(leaseStillValid: () -> Boolean) {
        check(leaseStillValid()) { "unlock_invalidated" }
    }
}
