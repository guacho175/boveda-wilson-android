package cl.bovedawilson.data.local.store

import androidx.room.withTransaction
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.dao.PendingConflictDao
import cl.bovedawilson.data.local.dao.SyncStateDao
import cl.bovedawilson.data.local.db.VaultDatabase
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.PendingConflictEntity
import cl.bovedawilson.data.local.entity.SyncStateEntity

@Suppress("TooManyFunctions")
class RoomEncryptedItemStore(
    private val database: VaultDatabase,
    private val dao: EncryptedItemDao,
    private val syncStateDao: SyncStateDao,
    private val conflictDao: PendingConflictDao
) : EncryptedItemStore {

    override suspend fun put(itemId: String, ciphertext: Ciphertext, metadata: ItemLocalMetadata) {
        val persistedCiphertext = if (metadata.tombstone) byteArrayOf() else ciphertext.bytes
        val entity = EncryptedItemEntity(
            itemId = itemId,
            ciphertext = persistedCiphertext,
            cryptoVersion = metadata.cryptoVersion,
            schemaVersion = metadata.schemaVersion,
            revision = metadata.revision,
            tombstone = metadata.tombstone,
            createdAt = metadata.createdAt,
            updatedAt = metadata.updatedAt,
            dirty = metadata.dirty,
            lastSyncedRevision = metadata.lastSyncedRevision,
            conflictOf = metadata.conflictOf,
            // We assume pending remote fields are not set on a normal put from local layer
            pendingRemoteCiphertext = null,
            pendingRemoteRevision = null,
            pendingRemoteCryptoVersion = null,
            pendingRemoteSchemaVersion = null,
            pendingRemoteTombstone = null,
            pendingRemoteCreatedAt = null,
            pendingRemoteUpdatedAt = null
        )
        dao.insertOrReplace(entity)
    }

    override suspend fun get(itemId: String): Pair<Ciphertext, ItemLocalMetadata>? {
        val entity = dao.getById(itemId) ?: return null

        val ciphertext = Ciphertext.fromPersisted(entity.ciphertext)
        val metadata = ItemLocalMetadata(
            cryptoVersion = entity.cryptoVersion,
            schemaVersion = entity.schemaVersion,
            revision = entity.revision,
            tombstone = entity.tombstone,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            dirty = entity.dirty,
            lastSyncedRevision = entity.lastSyncedRevision,
            conflictOf = entity.conflictOf
        )
        return Pair(ciphertext, metadata)
    }

    override suspend fun listActive(): List<StoredItem> = dao.getAllActive().map { entity ->
        StoredItem(
            itemId = entity.itemId,
            ciphertext = Ciphertext.fromPersisted(entity.ciphertext),
            metadata = entity.toLocalMetadata()
        )
    }

    override suspend fun delete(itemId: String) {
        database.withTransaction {
            val entity = dao.getById(itemId) ?: return@withTransaction
            check(entity.revision < Int.MAX_VALUE) { "Item revision exhausted" }
            check(entity.updatedAt < Long.MAX_VALUE) { "Item timestamp exhausted" }

            dao.insertOrReplace(
                entity.copy(
                    ciphertext = byteArrayOf(),
                    revision = entity.revision + 1,
                    tombstone = true,
                    updatedAt = maxOf(System.currentTimeMillis(), entity.updatedAt + 1),
                    dirty = true
                )
            )
        }
    }

    private fun EncryptedItemEntity.toLocalMetadata() = ItemLocalMetadata(
        cryptoVersion = cryptoVersion,
        schemaVersion = schemaVersion,
        revision = revision,
        tombstone = tombstone,
        createdAt = createdAt,
        updatedAt = updatedAt,
        dirty = dirty,
        lastSyncedRevision = lastSyncedRevision,
        conflictOf = conflictOf
    )

    override suspend fun getDirtySnapshots(): List<StoredItem> =
        dao.getAllDirtyItems().map { entity ->
            StoredItem(
                itemId = entity.itemId,
                ciphertext = Ciphertext.fromPersisted(entity.ciphertext),
                metadata = entity.toLocalMetadata()
            )
        }

    override suspend fun getLastPullAt(): Long? = syncStateDao.getLastPullAt()

    override suspend fun updateLastPullAt(timestamp: Long) {
        val current = syncStateDao.get()
        if (current != null) {
            syncStateDao.insertOrUpdate(current.copy(lastPullAt = timestamp))
        } else {
            syncStateDao.insertOrUpdate(
                SyncStateEntity(
                    id = 1,
                    lastPullAt = timestamp,
                    lastPushAt = 0L,
                    lastError = null
                )
            )
        }
    }

    override suspend fun getMaxAcceptedRevision(itemId: String): Int {
        return dao.getMaxAcceptedRevisionForItem(itemId) ?: 0
    }

    override suspend fun markPushSucceeded(itemId: String, uploadedRevision: Int): Boolean =
        dao.markPushSucceeded(itemId, uploadedRevision) == 1

    override suspend fun stageConflict(
        itemId: String,
        remoteItem: RemoteConflictItem,
        detectedAt: Long
    ) {
        database.withTransaction {
            val entity = dao.getById(itemId) ?: return@withTransaction
            dao.insertOrReplace(
                entity.copy(
                    pendingRemoteCiphertext = remoteItem.ciphertext,
                    pendingRemoteRevision = remoteItem.revision,
                    pendingRemoteCryptoVersion = remoteItem.cryptoVersion,
                    pendingRemoteSchemaVersion = remoteItem.schemaVersion,
                    pendingRemoteTombstone = remoteItem.tombstone,
                    pendingRemoteCreatedAt = remoteItem.createdAt,
                    pendingRemoteUpdatedAt = remoteItem.updatedAt
                )
            )
            conflictDao.insertOrReplace(
                PendingConflictEntity(
                    itemId = itemId,
                    detectedAt = detectedAt,
                    remoteRevision = remoteItem.revision
                )
            )
        }
    }

    override suspend fun getPendingConflicts(): List<PendingConflict> {
        return conflictDao.getAll().map { entity ->
            PendingConflict(
                itemId = entity.itemId,
                detectedAt = entity.detectedAt,
                remoteRevision = entity.remoteRevision
            )
        }
    }

    override suspend fun resolveAndInsertConflictCopy(data: ConflictResolutionData) {
        database.withTransaction {
            val officialCiphertext = if (data.remoteMetadata.tombstone) {
                byteArrayOf()
            } else {
                data.remoteCiphertext.bytes
            }
            val officialEntity = EncryptedItemEntity(
                itemId = data.originalItemId,
                ciphertext = officialCiphertext,
                cryptoVersion = data.remoteMetadata.cryptoVersion,
                schemaVersion = data.remoteMetadata.schemaVersion,
                revision = data.remoteMetadata.revision,
                tombstone = data.remoteMetadata.tombstone,
                createdAt = data.remoteMetadata.createdAt,
                updatedAt = data.remoteMetadata.updatedAt,
                dirty = false,
                lastSyncedRevision = data.remoteMetadata.revision,
                conflictOf = null,
                pendingRemoteCiphertext = null,
                pendingRemoteRevision = null,
                pendingRemoteCryptoVersion = null,
                pendingRemoteSchemaVersion = null,
                pendingRemoteTombstone = null,
                pendingRemoteCreatedAt = null,
                pendingRemoteUpdatedAt = null
            )
            dao.insertOrReplace(officialEntity)

            val copyEntity = EncryptedItemEntity(
                itemId = data.newItemId,
                ciphertext = if (data.localMetadata.tombstone) {
                    byteArrayOf()
                } else {
                    data.localCiphertext.bytes
                },
                cryptoVersion = data.localMetadata.cryptoVersion,
                schemaVersion = data.localMetadata.schemaVersion,
                revision = data.localMetadata.revision,
                tombstone = data.localMetadata.tombstone,
                createdAt = data.localMetadata.createdAt,
                updatedAt = data.localMetadata.updatedAt,
                dirty = true,
                lastSyncedRevision = 0,
                conflictOf = data.originalItemId,
                pendingRemoteCiphertext = null,
                pendingRemoteRevision = null,
                pendingRemoteCryptoVersion = null,
                pendingRemoteSchemaVersion = null,
                pendingRemoteTombstone = null,
                pendingRemoteCreatedAt = null,
                pendingRemoteUpdatedAt = null
            )
            dao.insertOrReplace(copyEntity)
            conflictDao.deleteById(data.originalItemId)
        }
    }

    override suspend fun clearConflictStaging(itemId: String) {
        database.withTransaction {
            val entity = dao.getById(itemId) ?: return@withTransaction
            dao.insertOrReplace(
                entity.copy(
                    pendingRemoteCiphertext = null,
                    pendingRemoteRevision = null,
                    pendingRemoteCryptoVersion = null,
                    pendingRemoteSchemaVersion = null,
                    pendingRemoteTombstone = null,
                    pendingRemoteCreatedAt = null,
                    pendingRemoteUpdatedAt = null
                )
            )
            conflictDao.deleteById(itemId)
        }
    }
}
