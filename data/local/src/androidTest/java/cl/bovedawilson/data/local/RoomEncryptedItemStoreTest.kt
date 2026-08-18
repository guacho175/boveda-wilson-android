package cl.bovedawilson.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.local.db.VaultDatabase
import cl.bovedawilson.data.local.entity.BiometricUnlockEntity
import cl.bovedawilson.data.local.entity.PendingConflictEntity
import cl.bovedawilson.data.local.entity.SyncStateEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.ConflictResolutionData
import cl.bovedawilson.data.local.store.ItemLocalMetadata
import cl.bovedawilson.data.local.store.RemoteConflictItem
import cl.bovedawilson.data.local.store.RoomEncryptedItemStore
import cl.bovedawilson.data.local.store.RoomLocalVaultDataWiper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomEncryptedItemStoreTest {

    private lateinit var database: VaultDatabase
    private lateinit var store: RoomEncryptedItemStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomEncryptedItemStore(
            database = database,
            dao = database.encryptedItemDao(),
            syncStateDao = database.syncStateDao(),
            conflictDao = database.pendingConflictDao()
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun deleteWritesEmptyMonotonicTombstone() = runBlocking {
        store.put(
            itemId = ITEM_ID,
            ciphertext = ciphertext(FIXTURE_LOCAL_CIPHERTEXT),
            metadata = metadata(revision = 4, updatedAt = 10L, dirty = false, lastSyncedRevision = 4)
        )

        store.delete(ITEM_ID)

        val (storedCiphertext, storedMetadata) = requireNotNull(store.get(ITEM_ID))
        assertArrayEquals(byteArrayOf(), storedCiphertext.bytes)
        assertEquals(5, storedMetadata.revision)
        assertTrue(storedMetadata.tombstone)
        assertTrue(storedMetadata.dirty)
        assertTrue(storedMetadata.updatedAt > 10L)
        assertEquals(4, storedMetadata.lastSyncedRevision)
    }

    @Test
    fun dirtySnapshotContainsCiphertextAndMatchingMetadataFromOneRow() = runBlocking {
        store.put(
            itemId = ITEM_ID,
            ciphertext = ciphertext(FIXTURE_LOCAL_CIPHERTEXT),
            metadata = metadata(revision = 7, updatedAt = 70L, dirty = true, lastSyncedRevision = 6)
        )
        store.put(
            itemId = CLEAN_ITEM_ID,
            ciphertext = ciphertext(FIXTURE_CLEAN_CIPHERTEXT),
            metadata = metadata(revision = 2, updatedAt = 20L, dirty = false, lastSyncedRevision = 2)
        )

        val snapshots = store.getDirtySnapshots()

        assertEquals(1, snapshots.size)
        val snapshot = snapshots.single()
        assertEquals(ITEM_ID, snapshot.itemId)
        assertArrayEquals(FIXTURE_LOCAL_CIPHERTEXT, snapshot.ciphertext.bytes)
        assertEquals(7, snapshot.metadata.revision)
        assertEquals(70L, snapshot.metadata.updatedAt)
        assertEquals(6, snapshot.metadata.lastSyncedRevision)
        assertTrue(snapshot.metadata.dirty)
    }

    @Test
    fun pushSuccessOnlyCleansMatchingRevision() = runBlocking {
        store.put(
            itemId = ITEM_ID,
            ciphertext = ciphertext(FIXTURE_LOCAL_CIPHERTEXT),
            metadata = metadata(revision = 7, updatedAt = 70L, dirty = true, lastSyncedRevision = 5)
        )

        assertFalse(store.markPushSucceeded(ITEM_ID, uploadedRevision = 6))
        val afterStaleUpload = requireNotNull(store.get(ITEM_ID)).second
        assertTrue(afterStaleUpload.dirty)
        assertEquals(5, afterStaleUpload.lastSyncedRevision)

        assertTrue(store.markPushSucceeded(ITEM_ID, uploadedRevision = 7))
        val afterCurrentUpload = requireNotNull(store.get(ITEM_ID)).second
        assertFalse(afterCurrentUpload.dirty)
        assertEquals(7, afterCurrentUpload.lastSyncedRevision)
        assertFalse(store.markPushSucceeded(ITEM_ID, uploadedRevision = 7))
    }

    @Test
    fun stageConflictRollsBackEntityWhenPendingInsertFails() = runBlocking {
        insertLocalItem()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_pending_insert
            BEFORE INSERT ON pending_conflicts
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END
            """.trimIndent()
        )

        var failed = false
        try {
            store.stageConflict(ITEM_ID, remoteConflict(), detectedAt = 300L)
        } catch (_: Exception) {
            failed = true
        }

        assertTrue("The injected pending insert failure must be observed", failed)
        val entity = requireNotNull(database.encryptedItemDao().getById(ITEM_ID))
        assertNull(entity.pendingRemoteCiphertext)
        assertNull(entity.pendingRemoteRevision)
        assertTrue(database.pendingConflictDao().getAll().isEmpty())
    }

    @Test
    fun resolutionReplacesOfficialCreatesCopyAndClearsPendingInOneCall() = runBlocking {
        insertLocalItem()
        store.stageConflict(ITEM_ID, remoteConflict(), detectedAt = 300L)

        store.resolveAndInsertConflictCopy(resolutionData())

        val official = requireNotNull(database.encryptedItemDao().getById(ITEM_ID))
        assertArrayEquals(FIXTURE_REMOTE_CIPHERTEXT, official.ciphertext)
        assertEquals(8, official.revision)
        assertFalse(official.dirty)
        assertEquals(8, official.lastSyncedRevision)
        assertNull(official.pendingRemoteCiphertext)

        val copy = requireNotNull(database.encryptedItemDao().getById(COPY_ITEM_ID))
        assertArrayEquals(FIXTURE_COPY_CIPHERTEXT, copy.ciphertext)
        assertTrue(copy.dirty)
        assertEquals(ITEM_ID, copy.conflictOf)
        assertTrue(database.pendingConflictDao().getAll().isEmpty())
    }

    @Test
    fun resolutionRollsBackAllWritesWhenCopyInsertFails() = runBlocking {
        insertLocalItem()
        store.stageConflict(ITEM_ID, remoteConflict(), detectedAt = 300L)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_conflict_copy
            BEFORE INSERT ON encrypted_items WHEN NEW.itemId = '$COPY_ITEM_ID'
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END
            """.trimIndent()
        )

        var failed = false
        try {
            store.resolveAndInsertConflictCopy(resolutionData())
        } catch (_: Exception) {
            failed = true
        }

        assertTrue("The injected copy insert failure must be observed", failed)
        val original = requireNotNull(database.encryptedItemDao().getById(ITEM_ID))
        assertArrayEquals(FIXTURE_LOCAL_CIPHERTEXT, original.ciphertext)
        assertTrue(original.dirty)
        assertArrayEquals(FIXTURE_REMOTE_CIPHERTEXT, original.pendingRemoteCiphertext)
        assertNull(database.encryptedItemDao().getById(COPY_ITEM_ID))
        assertEquals(1, database.pendingConflictDao().getAll().size)
    }

    @Test
    fun localVaultWiperClearsEveryRoomTable() = runBlocking {
        insertLocalItem()
        database.vaultMetaDao().insertOrUpdate(fixtureVaultMeta())
        database.pendingConflictDao().insertOrReplace(PendingConflictEntity(ITEM_ID, 20L, 8))
        database.biometricUnlockDao().insertOrUpdate(
            BiometricUnlockEntity(
                id = 1,
                keyAlias = "fixture-alias",
                wrappedBiometricKek = byteArrayOf(1),
                biometricWrappedVdek = byteArrayOf(2),
                biometricWrapEpoch = 1,
                iv = byteArrayOf(3),
                strongBoxBacked = false,
                createdAt = 10L
            )
        )
        database.syncStateDao().insertOrUpdate(SyncStateEntity(1, 10L, 10L, null))

        RoomLocalVaultDataWiper(database).wipeAllVaultData()

        assertNull(database.encryptedItemDao().getById(ITEM_ID))
        assertNull(database.vaultMetaDao().getMeta())
        assertTrue(database.pendingConflictDao().getAll().isEmpty())
        assertNull(database.biometricUnlockDao().get())
        assertNull(database.syncStateDao().get())
    }

    private suspend fun insertLocalItem() {
        store.put(
            itemId = ITEM_ID,
            ciphertext = ciphertext(FIXTURE_LOCAL_CIPHERTEXT),
            metadata = metadata(revision = 7, updatedAt = 70L, dirty = true, lastSyncedRevision = 6)
        )
    }

    private fun resolutionData() = ConflictResolutionData(
        originalItemId = ITEM_ID,
        newItemId = COPY_ITEM_ID,
        localCiphertext = ciphertext(FIXTURE_COPY_CIPHERTEXT),
        localMetadata = metadata(revision = 7, updatedAt = 80L, dirty = true, lastSyncedRevision = 0),
        remoteCiphertext = ciphertext(FIXTURE_REMOTE_CIPHERTEXT),
        remoteMetadata = remoteConflict()
    )

    private fun remoteConflict() = RemoteConflictItem(
        ciphertext = FIXTURE_REMOTE_CIPHERTEXT.copyOf(),
        cryptoVersion = 1,
        schemaVersion = 1,
        revision = 8,
        tombstone = false,
        createdAt = 10L,
        updatedAt = 90L
    )

    private fun metadata(
        revision: Int,
        updatedAt: Long,
        dirty: Boolean,
        lastSyncedRevision: Int
    ) = ItemLocalMetadata(
        cryptoVersion = 1,
        schemaVersion = 1,
        revision = revision,
        tombstone = false,
        createdAt = 10L,
        updatedAt = updatedAt,
        dirty = dirty,
        lastSyncedRevision = lastSyncedRevision,
        conflictOf = null
    )

    private fun ciphertext(bytes: ByteArray): Ciphertext = Ciphertext.fromPersisted(bytes.copyOf())

    private fun fixtureVaultMeta() = VaultMetaEntity(
        vaultId = "fixture-vault",
        ownerUid = "fixture-owner",
        schemaVersion = 1,
        cryptoVersion = 1,
        kdfName = "argon2id",
        kdfMemoryKib = 65_536,
        kdfIterations = 3,
        kdfParallelism = 4,
        kdfOutputLen = 32,
        passwordSalt = ByteArray(16) { 1 },
        passwordWrappedVdek = ByteArray(48) { 2 },
        recoverySalt = ByteArray(32) { 3 },
        recoveryWrappedVdek = ByteArray(48) { 4 },
        passwordWrapEpoch = 1,
        recoveryWrapEpoch = 1,
        createdAt = 10L,
        updatedAt = 10L,
        metaRevision = 1
    )

    private companion object {
        private const val ITEM_ID = "fixture-item"
        private const val CLEAN_ITEM_ID = "fixture-clean-item"
        private const val COPY_ITEM_ID = "fixture-copy-item"

        // Valores ficticios, no usar en producción.
        private val FIXTURE_LOCAL_CIPHERTEXT = byteArrayOf(1, 2, 3, 4)
        private val FIXTURE_CLEAN_CIPHERTEXT = byteArrayOf(5, 6, 7, 8)
        private val FIXTURE_REMOTE_CIPHERTEXT = byteArrayOf(9, 10, 11, 12)
        private val FIXTURE_COPY_CIPHERTEXT = byteArrayOf(13, 14, 15, 16)
    }
}
