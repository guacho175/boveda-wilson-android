package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.local.dao.BackupSizeStats
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.RemoteItemData
import cl.bovedawilson.data.remote.firestore.RemoteItemMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultData
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultValidator
import cl.bovedawilson.data.sync.backup.BackupItem
import cl.bovedawilson.data.sync.backup.BackupSnapshot
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRemotePublisherTest {
    @Test
    fun `remote conflict blocks every write`() = runBlocking {
        val fixture = fixture(remoteExists = true)
        fixture.remote.items[ITEM_ID] = fixture.remote.items.getValue(ITEM_ID).copy(revision = 2)

        val result = fixture.publisher.publish(fixture.snapshot)

        assertSame(AppError.RemoteConflict, (result as AppResult.Failure).error)
        assertEquals(0, fixture.remote.metadataWrites)
        assertEquals(0, fixture.remote.itemWrites)
    }

    @Test
    fun `matching baseline updates only wrappers with compare and set`() = runBlocking {
        val fixture = fixture(remoteExists = true)

        val result = fixture.publisher.publish(fixture.snapshot)

        assertTrue(result is AppResult.Success)
        assertEquals(1, fixture.remote.metadataWrites)
        assertEquals(0, fixture.remote.itemWrites)
        assertEquals(2, fixture.remote.metadata?.metaRevision)
        assertEquals(2, fixture.metaStore.meta?.metaRevision)
    }

    @Test
    fun `absent remote creates ciphertext idempotently`() = runBlocking {
        val fixture = fixture(remoteExists = false)

        val result = fixture.publisher.publish(fixture.snapshot)

        assertTrue(result is AppResult.Success)
        assertEquals(2, fixture.remote.metadataWrites)
        assertEquals(1, fixture.remote.itemWrites)
        assertEquals(CIPHERTEXT.toList(), fixture.remote.items.getValue(ITEM_ID).ciphertext.toList())
    }

    @Test
    fun `interrupted creation resumes items and finalizes metadata with CAS`() = runBlocking {
        val fixture = fixture(remoteExists = false, includeSecondItem = true)
        fixture.remote.failOnItemWriteNumber = 2

        val interrupted = fixture.publisher.publish(fixture.snapshot)

        assertTrue(interrupted is AppResult.Failure)
        assertEquals(1, fixture.remote.metadataWrites)
        assertEquals(1, fixture.remote.itemWrites)
        assertEquals(1, fixture.remote.metadata?.metaRevision)
        assertEquals(0, fixture.authorizer.consumes)

        val retried = fixture.publisher.publish(fixture.snapshot)

        assertTrue(retried is AppResult.Success)
        assertEquals(2, fixture.remote.metadataWrites)
        assertEquals(2, fixture.remote.itemWrites)
        assertEquals(2, fixture.remote.metadata?.metaRevision)
        assertEquals(1, fixture.authorizer.consumes)
    }

    private fun fixture(remoteExists: Boolean, includeSecondItem: Boolean = false): Fixture {
        val backupItems = buildList {
            add(backupItem(ITEM_ID, CIPHERTEXT))
            if (includeSecondItem) add(backupItem(SECOND_ITEM_ID, SECOND_CIPHERTEXT))
        }
        val snapshot = snapshot(backupItems)
        val local = restoredMeta()
        val localItems = backupItems.map { localItem(it.itemId, it.ciphertext) }
        val metaStore = FakeMetaStore(local)
        val itemDao = FakeItemDao(localItems.toMutableList())
        val remote = FakeRemote().apply {
            if (remoteExists) {
                metadata = snapshot.toRemoteMetadata()
                snapshot.items.forEach { items[it.itemId] = it.toRemoteItem() }
            }
        }
        val authorizer = RetriableAuthorizer()
        return Fixture(
            snapshot,
            metaStore,
            remote,
            authorizer,
            BackupRemotePublisher(
                FakeAuth(),
                remote,
                metaStore,
                itemDao,
                AppDispatchers(),
                SyncCoordinator(),
                authorizer,
            ),
        )
    }

    private data class Fixture(
        val snapshot: BackupSnapshot,
        val metaStore: FakeMetaStore,
        val remote: FakeRemote,
        val authorizer: RetriableAuthorizer,
        val publisher: BackupRemotePublisher,
    )

    private companion object {
        private const val UID = "fixture-uid"
        private const val ITEM_ID = "123e4567-e89b-42d3-a456-426614174000"
        private const val SECOND_ITEM_ID = "123e4567-e89b-42d3-a456-426614174002"
        private val CIPHERTEXT = byteArrayOf(9, 8, 7, 6)
        private val SECOND_CIPHERTEXT = byteArrayOf(6, 7, 8, 9)
    }

    private class FakeAuth : FirebaseAuthSource {
        override val isConfigured = true
        override val currentUserId: String = UID
        override suspend fun signInWithEmail(email: String, password: CharArray): String = unsupported()
        override suspend fun signUpWithEmail(email: String, password: CharArray): String = unsupported()
        override suspend fun signInWithGoogleIdToken(idToken: String): String = unsupported()
        override suspend fun signOut() = Unit
    }

    private class RetriableAuthorizer : BackupPublicationAuthorizer {
        var authorized = true
        var consumes = 0
        override fun authorize(
            snapshot: BackupSnapshot,
            restoredVault: cl.bovedawilson.core.crypto.session.UnlockedVault,
        ) = Unit
        override fun isAuthorized(snapshot: BackupSnapshot): Boolean = authorized
        override fun consume(snapshot: BackupSnapshot): Boolean {
            if (!authorized) return false
            authorized = false
            consumes++
            return true
        }
        override fun clear() { authorized = false }
    }

    private class FakeMetaStore(var meta: VaultMetaEntity?) : VaultMetaStore {
        override suspend fun getMeta(): VaultMetaEntity? = meta
        override fun observeMeta(): Flow<VaultMetaEntity?> = flowOf(meta)
        override suspend fun saveMeta(meta: VaultMetaEntity) { this.meta = meta }
        override suspend fun deleteAll() { meta = null }
    }

    private class FakeRemote : FirestoreVaultSource {
        var metadata: RemoteVaultMetadata? = null
        val items = linkedMapOf<String, RemoteItemData>()
        var metadataWrites = 0
        var itemWrites = 0
        var failOnItemWriteNumber: Int? = null

        override suspend fun createVaultMeta(
            expectedUid: String,
            vaultId: String,
            metadata: RemoteVaultMetadata,
        ) {
            val current = this.metadata
            if (current != null && !RemoteVaultValidator.hasSameContent(current, metadata)) {
                error("conflict")
            }
            if (this.metadata == null) metadataWrites++
            this.metadata = metadata
        }

        override suspend fun updateVaultMeta(
            expectedUid: String,
            vaultId: String,
            metadata: RemoteVaultMetadata,
        ) = unsupported()
        override suspend fun replaceVaultMetaIfUnchanged(
            expectedUid: String,
            vaultId: String,
            expected: RemoteVaultMetadata,
            replacement: RemoteVaultMetadata,
        ): Boolean {
            val current = metadata
            return if (current != null && RemoteVaultValidator.hasSameContent(current, expected)) {
                metadata = replacement
                metadataWrites++
                true
            } else {
                false
            }
        }

        override suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata? = metadata
        override suspend fun listVaults(expectedUid: String): List<RemoteVaultData> = unsupported()
        override suspend fun uploadItem(
            expectedUid: String,
            vaultId: String,
            itemId: String,
            ciphertext: Ciphertext,
            metadata: RemoteItemMetadata,
        ) = unsupported()

        override suspend fun createItemIfAbsentOrIdentical(
            expectedUid: String,
            vaultId: String,
            item: RemoteItemData,
        ): Boolean {
            val current = items[item.id]
            if (current != null) return current == item
            if (itemWrites + 1 == failOnItemWriteNumber) {
                failOnItemWriteNumber = null
                error("fixture interruption")
            }
            items[item.id] = item
            itemWrites++
            return true
        }

        override suspend fun getItem(
            expectedUid: String,
            vaultId: String,
            itemId: String,
        ): Pair<Ciphertext, RemoteItemMetadata>? = unsupported()

        override suspend fun listItems(
            expectedUid: String,
            vaultId: String,
        ): List<RemoteItemData> = items.values.toList()
        override suspend fun listDeletedVaultIds(expectedUid: String): Set<String> = emptySet()
        override suspend fun purgeVault(
            expectedUid: String,
            vaultId: String,
            deletedAt: Long,
        ) = unsupported()
    }

    private class FakeItemDao(private val items: MutableList<EncryptedItemEntity>) : EncryptedItemDao {
        override fun observeAllActive(): Flow<List<EncryptedItemEntity>> = flowOf(items)
        override suspend fun getAllActive(): List<EncryptedItemEntity> = items.filterNot { it.tombstone }
        override suspend fun getAllItems(): List<EncryptedItemEntity> = items.toList()
        override suspend fun getBackupSizeStats(): BackupSizeStats = BackupSizeStats(
            itemCount = items.size.toLong(),
            ciphertextBytes = items.sumOf { it.ciphertext.size.toLong() },
            maxCiphertextBytes = items.maxOfOrNull { it.ciphertext.size }?.toLong() ?: 0,
        )
        override suspend fun countActive(): Int = getAllActive().size
        override suspend fun getById(itemId: String): EncryptedItemEntity? = items.find { it.itemId == itemId }
        override fun observeById(itemId: String): Flow<EncryptedItemEntity?> =
            flowOf(items.find { it.itemId == itemId })
        override suspend fun insertOrReplace(item: EncryptedItemEntity) = unsupported()
        override suspend fun update(item: EncryptedItemEntity) = unsupported()
        override suspend fun insertOrReplaceAll(items: List<EncryptedItemEntity>) = unsupported()
        override suspend fun deleteAll() = unsupported()
        override suspend fun getAllDirtyItems(): List<EncryptedItemEntity> = items.filter { it.dirty }
        override suspend fun markPushSucceeded(itemId: String, uploadedRevision: Int): Int = 1
        override suspend fun getMaxAcceptedRevisionForItem(itemId: String): Int? = null
    }
}

private fun snapshot(
    items: List<BackupItem> = listOf(
        backupItem("123e4567-e89b-42d3-a456-426614174000", byteArrayOf(9, 8, 7, 6)),
    ),
) = BackupSnapshot(
    magic = "bw-vault-backup",
    formatVersion = 2,
    cryptoVersion = 1,
    schemaVersion = 1,
    passwordWrapEpoch = 1,
    recoveryWrapEpoch = 1,
    vaultId = "123e4567-e89b-42d3-a456-426614174001",
    createdAt = 1,
    updatedAt = 1,
    metaRevision = 1,
    kdfName = "argon2id",
    kdfMemoryKib = 65_536,
    kdfIterations = 3,
    kdfParallelism = 4,
    kdfOutputLen = 32,
    passwordSalt = ByteArray(16) { 1 },
    recoverySalt = ByteArray(32) { 2 },
    passwordWrappedVdek = byteArrayOf(1, 2),
    recoveryWrappedVdek = byteArrayOf(3, 4),
    manifestAuthenticator = ByteArray(33) { 5 },
    items = items,
)

private fun restoredMeta() = VaultMetaEntity(
    vaultId = "123e4567-e89b-42d3-a456-426614174001",
    ownerUid = "",
    schemaVersion = 1,
    cryptoVersion = 1,
    kdfName = "argon2id",
    kdfMemoryKib = 65_536,
    kdfIterations = 3,
    kdfParallelism = 4,
    kdfOutputLen = 32,
    passwordSalt = ByteArray(16) { 5 },
    passwordWrappedVdek = byteArrayOf(5, 6),
    recoverySalt = ByteArray(32) { 6 },
    recoveryWrappedVdek = byteArrayOf(7, 8),
    passwordWrapEpoch = 2,
    recoveryWrapEpoch = 2,
    createdAt = 1,
    updatedAt = 1,
    metaRevision = 1,
)

private fun localItem(itemId: String, ciphertext: ByteArray) = EncryptedItemEntity(
    itemId = itemId,
    ciphertext = ciphertext.copyOf(),
    cryptoVersion = 1,
    schemaVersion = 1,
    revision = 1,
    tombstone = false,
    createdAt = 1,
    updatedAt = 1,
    dirty = true,
    lastSyncedRevision = 0,
    conflictOf = null,
    pendingRemoteCiphertext = null,
    pendingRemoteRevision = null,
    pendingRemoteCryptoVersion = null,
    pendingRemoteSchemaVersion = null,
    pendingRemoteTombstone = null,
    pendingRemoteCreatedAt = null,
    pendingRemoteUpdatedAt = null,
)

private fun backupItem(itemId: String, ciphertext: ByteArray) =
    BackupItem(itemId, ciphertext.copyOf(), 1, 1, 1, false, 1, 1)

private fun BackupSnapshot.toRemoteMetadata() = RemoteVaultMetadata(
    schemaVersion = schemaVersion,
    cryptoVersion = cryptoVersion,
    kdfName = kdfName,
    kdfMemoryKib = kdfMemoryKib,
    kdfIterations = kdfIterations,
    kdfParallelism = kdfParallelism,
    kdfOutputLen = kdfOutputLen,
    passwordSalt = passwordSalt,
    passwordWrappedVdek = passwordWrappedVdek,
    recoverySalt = recoverySalt,
    recoveryWrappedVdek = recoveryWrappedVdek,
    passwordWrapEpoch = passwordWrapEpoch,
    recoveryWrapEpoch = recoveryWrapEpoch,
    createdAt = createdAt,
    updatedAt = updatedAt,
    metaRevision = metaRevision,
)

private fun BackupItem.toRemoteItem() = RemoteItemData(
    id = itemId,
    ciphertext = ciphertext,
    cryptoVersion = cryptoVersion,
    schemaVersion = schemaVersion,
    revision = revision,
    tombstone = tombstone,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun unsupported(): Nothing = error("unsupported fixture operation")
