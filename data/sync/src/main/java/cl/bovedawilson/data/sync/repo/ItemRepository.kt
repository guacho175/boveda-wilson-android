package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.core.model.VaultItem
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.local.store.ItemLocalMetadata
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import cl.bovedawilson.data.sync.mapper.VaultItemMapper
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.NoOpSyncScheduler
import cl.bovedawilson.data.sync.worker.SyncScheduler

class ItemRepository(
    private val localStore: EncryptedItemStore,
    // Reservado para el push de la Fase 5 (sincronización): ItemRepository todavía
    // solo escribe en Room, el envío a Firestore llega con SyncEngine/SyncWorker.
    @Suppress("UnusedPrivateProperty")
    private val remoteSource: FirestoreVaultSource,
    private val session: VaultSession,
    private val syncScheduler: SyncScheduler = NoOpSyncScheduler,
    private val coordinator: SyncCoordinator = SyncCoordinator(),
) {
    suspend fun saveItem(item: VaultItem) = coordinator.exclusive {
        val state = session.state.value
        check(state is SessionState.Unlocked) { "Vault is locked" }
        val vault = session.getVault() ?: error("Vault is locked")

        // Para simplificar, asumimos v1 por ahora.
        val schemaVersion = SchemaVersion(1)
        val cryptoVersion = CryptoVersion(1)

        val payload = VaultItemMapper.toPayload(item)
        val aad = AadBuilder.forItem(
            vaultId = state.vaultId,
            itemId = item.id,
            schemaVersion = schemaVersion,
            cryptoVersion = cryptoVersion
        )
        val ciphertext = vault.encrypt(payload, aad)

        // La revisión avanza sobre lo ya guardado; si se reiniciara en 1 en cada
        // edición, el servidor rechazaría el push por retroceso de `revision`
        // (`firestore.rules`, G-58) y la sincronización quedaría atascada.
        val previous = localStore.get(item.id)?.second
        val meta = ItemLocalMetadata(
            cryptoVersion = cryptoVersion.value,
            schemaVersion = schemaVersion.value,
            revision = (previous?.revision ?: 0) + 1,
            tombstone = false,
            createdAt = previous?.createdAt ?: item.createdAt,
            updatedAt = item.updatedAt,
            dirty = true,
            lastSyncedRevision = previous?.lastSyncedRevision ?: 0,
            conflictOf = previous?.conflictOf
        )
        localStore.put(item.id, ciphertext, meta)
        syncScheduler.syncNowIfAuthorized()
    }

    /**
     * Descifra en memoria todos los ítems vivos. El límite práctico documentado es de
     * 5 000 ítems (R-08); el descifrado paginado queda fuera del MVP.
     */
    suspend fun listItems(): List<VaultItem> {
        val state = session.state.value
        check(state is SessionState.Unlocked) { "Vault is locked" }
        val vault = session.getVault() ?: error("Vault is locked")

        return localStore.listActive().map { stored ->
            val aad = AadBuilder.forItem(
                vaultId = state.vaultId,
                itemId = stored.itemId,
                schemaVersion = SchemaVersion(stored.metadata.schemaVersion),
                cryptoVersion = CryptoVersion(stored.metadata.cryptoVersion)
            )
            VaultItemMapper.toDomain(stored.itemId, vault.decrypt(stored.ciphertext, aad))
        }
    }

    suspend fun getItem(itemId: String): VaultItem? {
        val state = session.state.value
        check(state is SessionState.Unlocked) { "Vault is locked" }
        val vault = session.getVault() ?: error("Vault is locked")

        val result = localStore.get(itemId) ?: return null
        val (ciphertext, meta) = result

        val schemaVersion = SchemaVersion(meta.schemaVersion)
        val cryptoVersion = CryptoVersion(meta.cryptoVersion)

        val aad = AadBuilder.forItem(
            vaultId = state.vaultId,
            itemId = itemId,
            schemaVersion = schemaVersion,
            cryptoVersion = cryptoVersion
        )
        val payload = vault.decrypt(ciphertext, aad)

        return VaultItemMapper.toDomain(itemId, payload)
    }

    suspend fun deleteItem(itemId: String) = coordinator.exclusive {
        val state = session.state.value
        check(state is SessionState.Unlocked) { "Vault is locked" }
        localStore.delete(itemId)
        syncScheduler.syncNowIfAuthorized()
    }
}
