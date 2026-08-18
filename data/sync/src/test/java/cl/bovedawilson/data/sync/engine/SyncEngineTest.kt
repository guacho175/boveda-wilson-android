package cl.bovedawilson.data.sync.engine

import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.data.local.dao.BackupSizeStats
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.ConflictResolutionData
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.local.store.ItemLocalMetadata
import cl.bovedawilson.data.local.store.PendingConflict
import cl.bovedawilson.data.local.store.RemoteConflictItem
import cl.bovedawilson.data.local.store.StoredItem
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.RemoteItemData
import cl.bovedawilson.data.remote.firestore.RemoteItemMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultData
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.sync.session.VaultSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas del protocolo con dobles manuales; ninguna depende de Firebase ni de Room. */
class SyncEngineTest {

    @Test
    fun `push sube el snapshot real y confirma la revision`() = runTest {
        val fixture = fixture()
        fixture.store.seed(storedItem(revision = 2, lastSyncedRevision = 1))

        val result = fixture.engine.sync()

        assertTrue(result.success)
        assertEquals(1, result.itemsPushed)
        assertEquals(1, fixture.remote.uploads.size)
        assertTrue(fixture.remote.uploads.single().ciphertext.bytes.contentEquals(FIXTURE_CIPHERTEXT))
        assertFalse(requireNotNull(fixture.store.item(ITEM_ID)).metadata.dirty)
    }

    @Test
    fun `push no sobrescribe una escritura concurrente y conserva dirty`() = runTest {
        val fixture = fixture()
        fixture.store.seed(storedItem(revision = 2, lastSyncedRevision = 1))
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 1)
        fixture.remote.beforeItemCas = {
            fixture.remote.items[ITEM_ID] = remoteItem(revision = 2)
        }

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("PROTOCOL", result.errors.single().code)
        assertEquals(2, requireNotNull(fixture.remote.items[ITEM_ID]).second.revision)
        assertTrue(requireNotNull(fixture.store.item(ITEM_ID)).metadata.dirty)
    }

    @Test
    fun `un fallo de red durante upload conserva dirty`() = runTest {
        val fixture = fixture()
        fixture.store.seed(storedItem())
        fixture.remote.failUpload = true

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("NETWORK", result.errors.single().code)
        assertTrue(requireNotNull(fixture.store.item(ITEM_ID)).metadata.dirty)
    }

    @Test
    fun `pull persiste ciphertext y acota el cursor al reloj local`() = runTest {
        val fixture = fixture(now = LOCAL_NOW)
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 4, updatedAt = FUTURE_REMOTE_TIME)

        val result = fixture.engine.sync()

        assertTrue(result.success)
        assertEquals(1, result.itemsPulled)
        val stored = requireNotNull(fixture.store.item(ITEM_ID))
        assertTrue(stored.ciphertext.bytes.contentEquals(FIXTURE_REMOTE_CIPHERTEXT))
        assertFalse(stored.metadata.dirty)
        assertEquals(4, stored.metadata.lastSyncedRevision)
        assertEquals(LOCAL_NOW, fixture.store.lastPullAt)
    }

    @Test
    fun `segundo pull acepta revision nueva aunque updatedAt sea anterior al cursor`() = runTest {
        val fixture = fixture(now = LOCAL_NOW)
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 1, updatedAt = PREVIOUS_CURSOR)
        val first = fixture.engine.sync()
        assertTrue(first.errors.toString(), first.success)

        fixture.remote.items[ITEM_ID] = remoteItem(revision = 2, updatedAt = PREVIOUS_CURSOR - 1)
        val second = fixture.engine.sync()

        assertTrue(second.success)
        assertEquals(1, second.itemsUpdated)
        assertEquals(2, requireNotNull(fixture.store.item(ITEM_ID)).metadata.revision)
    }

    @Test
    fun `pull reemplaza un registro limpio solo con revision superior`() = runTest {
        val fixture = fixture()
        fixture.store.seed(
            storedItem(revision = 2, lastSyncedRevision = 2).copy(
                metadata = metadata(revision = 2, lastSyncedRevision = 2, dirty = false)
            )
        )
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 3)

        val result = fixture.engine.sync()

        assertTrue(result.success)
        assertEquals(1, result.itemsUpdated)
        val stored = requireNotNull(fixture.store.item(ITEM_ID))
        assertEquals(3, stored.metadata.revision)
        assertTrue(stored.ciphertext.bytes.contentEquals(FIXTURE_REMOTE_CIPHERTEXT))
    }

    @Test
    fun `pull rechaza tombstone remoto que conserva ciphertext`() = runTest {
        val fixture = fixture()
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 3, tombstone = true)

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("PROTOCOL", result.errors.single().code)
        assertNull(fixture.store.item(ITEM_ID))
    }

    @Test
    fun `pull acepta tombstone remoto canonico sin ciphertext`() = runTest {
        val fixture = fixture()
        fixture.remote.items[ITEM_ID] = ciphertextOf(byteArrayOf()) to RemoteItemMetadata(
            cryptoVersion = 1,
            schemaVersion = 1,
            revision = 3,
            tombstone = true,
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT
        )

        val result = fixture.engine.sync()

        assertTrue(result.success)
        val stored = requireNotNull(fixture.store.item(ITEM_ID))
        assertTrue(stored.metadata.tombstone)
        assertEquals(0, stored.ciphertext.bytes.size)
        assertEquals(3, stored.metadata.lastSyncedRevision)
    }

    @Test
    fun `conflicto bloqueado usa staging y se resuelve tras unlock sin otra lectura remota`() = runTest {
        val fixture = fixture()
        val encryptedFixture = encryptedLocalFixture()
        fixture.store.seed(
            storedItem(
                ciphertext = encryptedFixture.ciphertext.bytes,
                revision = 2,
                lastSyncedRevision = 1
            )
        )
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 3)

        val lockedResult = fixture.engine.sync()

        assertTrue(lockedResult.success)
        assertEquals(1, lockedResult.conflictsDetected)
        assertTrue(
            requireNotNull(fixture.store.item(ITEM_ID))
                .ciphertext.bytes.contentEquals(encryptedFixture.ciphertext.bytes)
        )
        assertEquals(1, fixture.store.pending.size)
        val readsBeforeUnlock = fixture.remote.readOperations

        val lease = requireNotNull(fixture.session.beginUnlock())
        fixture.resolver.resolveAllPending(encryptedFixture.vault, VAULT_ID) {
            fixture.session.isUnlockLeaseValid(lease)
        }
        assertTrue(fixture.session.tryUnlock(lease, encryptedFixture.vault, VAULT_ID))

        assertEquals(readsBeforeUnlock, fixture.remote.readOperations)
        assertTrue(fixture.store.pending.isEmpty())
        val official = requireNotNull(fixture.store.item(ITEM_ID))
        assertEquals(3, official.metadata.revision)
        assertFalse(official.metadata.dirty)
        val conflictCopy = fixture.store.allItems().single { it.metadata.conflictOf == ITEM_ID }
        assertTrue(conflictCopy.metadata.dirty)
    }

    @Test
    fun `acepta exactamente 256 KiB y rechaza un byte adicional`() = runTest {
        val exact = fixture()
        exact.store.seed(storedItem(ciphertext = ByteArray(MAX_BYTES) { 7 }))
        val accepted = exact.engine.sync()

        assertTrue(accepted.success)
        assertEquals(1, exact.remote.uploads.size)

        val oversized = fixture()
        oversized.store.seed(storedItem(ciphertext = ByteArray(MAX_BYTES + 1) { 7 }))
        val rejected = oversized.engine.sync()

        assertFalse(rejected.success)
        assertEquals("PROTOCOL", rejected.errors.single().code)
        assertTrue(oversized.remote.uploads.isEmpty())
        assertTrue(requireNotNull(oversized.store.item(ITEM_ID)).metadata.dirty)
    }

    @Test
    fun `push rechaza tombstone con ciphertext no vacio`() = runTest {
        val fixture = fixture()
        fixture.store.seed(
            storedItem().copy(metadata = metadata(revision = 2, lastSyncedRevision = 1, tombstone = true))
        )

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("PROTOCOL", result.errors.single().code)
        assertTrue(fixture.remote.uploads.isEmpty())
    }

    @Test
    fun `primera subida permite revision mayor que uno`() = runTest {
        val fixture = fixture()
        fixture.store.seed(storedItem(revision = 7, lastSyncedRevision = 0))

        val result = fixture.engine.sync()

        assertTrue(result.success)
        assertEquals(7, fixture.remote.uploads.single().metadata.revision)
    }

    @Test
    fun `push rechaza rollback remoto sin sobrescribir`() = runTest {
        val fixture = fixture()
        fixture.store.seed(storedItem(revision = 6, lastSyncedRevision = 5))
        fixture.remote.items[ITEM_ID] = remoteItemPair(revision = 4)
        fixture.remote.listOverride = emptyList()

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("ROLLBACK", result.errors.single().code)
        assertTrue(fixture.remote.uploads.isEmpty())
        assertTrue(requireNotNull(fixture.store.item(ITEM_ID)).metadata.dirty)
    }

    @Test
    fun `edicion concurrente durante upload no limpia dirty`() = runTest {
        val fixture = fixture()
        fixture.store.seed(storedItem(revision = 2, lastSyncedRevision = 1))
        fixture.remote.onUpload = {
            fixture.store.editRevision(ITEM_ID, revision = 3)
        }

        val result = fixture.engine.sync()

        assertTrue(result.success)
        assertEquals(0, result.itemsPushed)
        val current = requireNotNull(fixture.store.item(ITEM_ID))
        assertEquals(3, current.metadata.revision)
        assertEquals(1, current.metadata.lastSyncedRevision)
        assertTrue(current.metadata.dirty)
        assertEquals(2, fixture.remote.uploads.single().metadata.revision)
    }

    @Test
    fun `error de pull no produce success ni avanza cursor`() = runTest {
        val fixture = fixture()
        fixture.store.lastPullAt = PREVIOUS_CURSOR
        fixture.remote.failList = true

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("NETWORK", result.errors.single().code)
        assertEquals(PREVIOUS_CURSOR, fixture.store.lastPullAt)
    }

    @Test
    fun `pull rechaza revision inferior a watermark y conserva cursor`() = runTest {
        val fixture = fixture()
        fixture.store.lastPullAt = PREVIOUS_CURSOR
        fixture.store.watermarks[ITEM_ID] = 5
        fixture.remote.items[ITEM_ID] = remoteItem(revision = 4, updatedAt = PREVIOUS_CURSOR + 1)

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("ROLLBACK", result.errors.single().code)
        assertNull(fixture.store.item(ITEM_ID))
        assertEquals(PREVIOUS_CURSOR, fixture.store.lastPullAt)
    }

    @Test
    fun `owner vacio o distinto falla cerrado antes de tocar la red`() = runTest {
        val fixture = fixture(ownerUid = "")

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errors.single().code)
        assertEquals(0, fixture.remote.readOperations)
    }

    @Test
    fun `marker terminal bloquea pull y push antes de leer items`() = runTest {
        val fixture = fixture()
        fixture.remote.deletedVaultIds += VAULT_ID
        fixture.store.seed(storedItem())

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errors.single().code)
        assertEquals(0, fixture.remote.readOperations)
        assertTrue(fixture.remote.uploads.isEmpty())
    }

    @Test
    fun `metadata local con revision mayor se sube y el retry exacto es estable`() = runTest {
        val fixture = fixture()
        val updated = vaultMeta(OWNER_UID).copy(metaRevision = 2, updatedAt = UPDATED_AT + 1)
        fixture.metaStore.saveMeta(updated)

        val first = fixture.engine.sync()
        assertTrue(first.errors.toString(), first.success)
        assertEquals(2, fixture.remote.vaultMetadata?.metaRevision)
        assertTrue(fixture.engine.sync().success)
        assertEquals(1, fixture.remote.vaultUploadCalls)
    }

    @Test
    fun `metadata concurrente no se sobrescribe`() = runTest {
        val fixture = fixture()
        fixture.metaStore.saveMeta(vaultMeta(OWNER_UID).copy(metaRevision = 2, updatedAt = UPDATED_AT + 1))
        val concurrent = vaultMeta(OWNER_UID).copy(
            metaRevision = 3,
            updatedAt = UPDATED_AT + 2,
            recoveryWrappedVdek = ByteArray(49) { 9 },
            recoveryWrapEpoch = 2,
        ).toRemoteMetadataForTest()
        fixture.remote.beforeMetaCas = { fixture.remote.vaultMetadata = concurrent }

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals("PROTOCOL", result.errors.single().code)
        assertTrue(requireNotNull(fixture.remote.vaultMetadata).hasSameContentForTest(concurrent))
    }

    @Test
    fun `metadata remota con revision mayor actualiza el envoltorio local`() = runTest {
        val fixture = fixture()
        fixture.remote.vaultMetadata = vaultMeta(OWNER_UID)
            .copy(
                metaRevision = 2,
                updatedAt = UPDATED_AT + 1,
                passwordWrappedVdek = ByteArray(49) { 7 },
                passwordWrapEpoch = 2
            )
            .toRemoteMetadataForTest()

        val result = fixture.engine.sync()

        assertTrue(result.errors.toString(), result.success)
        assertEquals(2, fixture.metaStore.getMeta()?.metaRevision)
        assertEquals(2, fixture.metaStore.getMeta()?.passwordWrapEpoch)
    }

    @Test
    fun `metadata remota no pisa una reenvoltura local concurrente`() = runTest {
        val fixture = fixture()
        fixture.remote.vaultMetadata = vaultMeta(OWNER_UID).copy(
            metaRevision = 2,
            updatedAt = UPDATED_AT + 1,
            passwordWrappedVdek = ByteArray(49) { 7 },
            passwordWrapEpoch = 2,
        ).toRemoteMetadataForTest()
        val concurrent = vaultMeta(OWNER_UID).copy(
            metaRevision = 3,
            updatedAt = UPDATED_AT + 2,
            recoveryWrappedVdek = ByteArray(49) { 8 },
            recoveryWrapEpoch = 2,
        )
        fixture.metaStore.beforeReplace = { fixture.metaStore.mutate(concurrent) }

        val result = fixture.engine.sync()

        assertFalse(result.success)
        assertEquals(concurrent, fixture.metaStore.getMeta())
    }

    private fun fixture(
        now: Long = LOCAL_NOW,
        ownerUid: String = OWNER_UID
    ): EngineFixture {
        val dao = FakeEncryptedItemDao()
        val store = FakeEncryptedItemStore(dao)
        val session = VaultSession()
        val resolver = ConflictResolver(store, dao)
        val meta = vaultMeta(ownerUid)
        val remote = FakeFirestoreVaultSource().apply {
            vaultMetadata = meta.toRemoteMetadataForTest()
        }
        val metaStore = FakeVaultMetaStore(meta)
        val engine = SyncEngine(
            localStore = store,
            remoteSource = remote,
            vaultMetaStore = metaStore,
            authSource = FakeFirebaseAuthSource(OWNER_UID),
            conflictResolver = resolver,
            nowMillis = { now }
        )
        return EngineFixture(engine, store, remote, metaStore, session, resolver)
    }

    private fun encryptedLocalFixture(): EncryptedFixture {
        val vault = newTestVault()
        val payload = ItemPayload(
            v = 1,
            title = "FIXTURE conflict title",
            body = "FIXTURE conflict body",
            tags = emptyList(),
            fields = emptyList(),
            createdAt = CREATED_AT,
            updatedAt = UPDATED_AT
        )
        val aad = AadBuilder.forItem(
            vaultId = VAULT_ID,
            itemId = ITEM_ID,
            schemaVersion = SchemaVersion(1),
            cryptoVersion = CryptoVersion(1)
        )
        return EncryptedFixture(vault, vault.encrypt(payload, aad))
    }

    /** La fábrica es `internal` por diseño. La reflexión queda confinada a la prueba para
     * obtener una VDEK Tink efímera sin ejecutar Argon2id ni inventar una primitiva. */
    private fun newTestVault(): UnlockedVault {
        val factory = UnlockedVault.Companion::class.java.declaredMethods.single {
            it.name.startsWith("withNewVdek")
        }
        factory.isAccessible = true
        return factory.invoke(UnlockedVault.Companion) as UnlockedVault
    }

    private fun storedItem(
        ciphertext: ByteArray = FIXTURE_CIPHERTEXT,
        revision: Int = 2,
        lastSyncedRevision: Int = 1
    ) = StoredItem(
        itemId = ITEM_ID,
        ciphertext = ciphertextOf(ciphertext),
        metadata = metadata(revision = revision, lastSyncedRevision = lastSyncedRevision)
    )

    private fun remoteItem(
        revision: Int,
        tombstone: Boolean = false,
        updatedAt: Long = UPDATED_AT
    ): Pair<Ciphertext, RemoteItemMetadata> = remoteItemPair(revision, tombstone, updatedAt)

    private fun remoteItemPair(
        revision: Int,
        tombstone: Boolean = false,
        updatedAt: Long = UPDATED_AT
    ) = ciphertextOf(FIXTURE_REMOTE_CIPHERTEXT) to RemoteItemMetadata(
        cryptoVersion = 1,
        schemaVersion = 1,
        revision = revision,
        tombstone = tombstone,
        createdAt = CREATED_AT,
        updatedAt = updatedAt
    )

    private fun metadata(
        revision: Int,
        lastSyncedRevision: Int,
        dirty: Boolean = true,
        tombstone: Boolean = false,
        conflictOf: String? = null
    ) = ItemLocalMetadata(
        cryptoVersion = 1,
        schemaVersion = 1,
        revision = revision,
        tombstone = tombstone,
        createdAt = CREATED_AT,
        updatedAt = UPDATED_AT,
        dirty = dirty,
        lastSyncedRevision = lastSyncedRevision,
        conflictOf = conflictOf
    )

    private fun ciphertextOf(bytes: ByteArray) = Ciphertext.fromPersisted(bytes.copyOf())

    private fun vaultMeta(ownerUid: String) = VaultMetaEntity(
        vaultId = VAULT_ID,
        ownerUid = ownerUid,
        schemaVersion = 1,
        cryptoVersion = 1,
        kdfName = "argon2id",
        kdfMemoryKib = 65_536,
        kdfIterations = 3,
        kdfParallelism = 4,
        kdfOutputLen = 32,
        passwordSalt = ByteArray(16) { 1 },
        passwordWrappedVdek = byteArrayOf(2),
        recoverySalt = ByteArray(32) { 3 },
        recoveryWrappedVdek = byteArrayOf(4),
        passwordWrapEpoch = 1,
        recoveryWrapEpoch = 1,
        createdAt = CREATED_AT,
        updatedAt = UPDATED_AT,
        metaRevision = 1
    )

    private data class EngineFixture(
        val engine: SyncEngine,
        val store: FakeEncryptedItemStore,
        val remote: FakeFirestoreVaultSource,
        val metaStore: FakeVaultMetaStore,
        val session: VaultSession,
        val resolver: ConflictResolver
    )

    private data class EncryptedFixture(
        val vault: UnlockedVault,
        val ciphertext: Ciphertext
    )

    private companion object {
        const val VAULT_ID = "11111111-1111-4111-8111-111111111111"
        const val ITEM_ID = "fixture-item-id"
        const val OWNER_UID = "fixture-owner-uid"
        const val CREATED_AT = 100L
        const val UPDATED_AT = 200L
        const val LOCAL_NOW = 1_000L
        const val FUTURE_REMOTE_TIME = 9_000L
        const val PREVIOUS_CURSOR = 50L
        const val MAX_BYTES = 262_144
        val FIXTURE_CIPHERTEXT = byteArrayOf(10, 11, 12)
        val FIXTURE_REMOTE_CIPHERTEXT = byteArrayOf(20, 21, 22)
    }
}

private class FakeVaultMetaStore(initial: VaultMetaEntity?) : VaultMetaStore {
    private val state = MutableStateFlow(initial)
    var beforeReplace: (() -> Unit)? = null
    override suspend fun getMeta(): VaultMetaEntity? = state.value
    override fun observeMeta(): Flow<VaultMetaEntity?> = state
    override suspend fun saveMeta(meta: VaultMetaEntity) { state.value = meta }
    override suspend fun replaceIfUnchanged(
        expected: VaultMetaEntity,
        replacement: VaultMetaEntity,
    ): Boolean {
        beforeReplace?.invoke()
        beforeReplace = null
        if (state.value != expected) return false
        state.value = replacement
        return true
    }
    fun mutate(meta: VaultMetaEntity) { state.value = meta }
    override suspend fun deleteAll() { state.value = null }
}

private class FakeFirebaseAuthSource(override var currentUserId: String?) : FirebaseAuthSource {
    override val isConfigured: Boolean = true
    override suspend fun signInWithEmail(email: String, password: CharArray): String = try {
        requireNotNull(currentUserId)
    } finally {
        Wipe.chars(password)
    }

    override suspend fun signUpWithEmail(email: String, password: CharArray): String = try {
        requireNotNull(currentUserId)
    } finally {
        Wipe.chars(password)
    }
    override suspend fun signInWithGoogleIdToken(idToken: String): String = requireNotNull(currentUserId)
    override suspend fun signOut() { currentUserId = null }
}

private data class Upload(
    val itemId: String,
    val ciphertext: Ciphertext,
    val metadata: RemoteItemMetadata
)

private class FakeFirestoreVaultSource : FirestoreVaultSource {
    val items = linkedMapOf<String, Pair<Ciphertext, RemoteItemMetadata>>()
    val uploads = mutableListOf<Upload>()
    var failList = false
    var failUpload = false
    var onUpload: (() -> Unit)? = null
    var beforeItemCas: (() -> Unit)? = null
    var beforeMetaCas: (() -> Unit)? = null
    var listOverride: List<RemoteItemData>? = null
    val deletedVaultIds = mutableSetOf<String>()
    var vaultMetadata: RemoteVaultMetadata? = null
    var vaultUploadCalls = 0
    var readOperations = 0

    override suspend fun createVaultMeta(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ) = storeVaultMetadata(metadata)

    override suspend fun updateVaultMeta(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ) = storeVaultMetadata(metadata)

    override suspend fun replaceVaultMetaIfUnchanged(
        expectedUid: String,
        vaultId: String,
        expected: RemoteVaultMetadata,
        replacement: RemoteVaultMetadata,
    ): Boolean {
        beforeMetaCas?.invoke()
        val current = vaultMetadata
        if (current == null || !current.hasSameContentForTest(expected)) return false
        vaultMetadata = replacement
        vaultUploadCalls += 1
        return true
    }

    private fun storeVaultMetadata(
        metadata: RemoteVaultMetadata
    ) {
        val current = vaultMetadata
        if (current == null || metadata.metaRevision > current.metaRevision) {
            vaultMetadata = metadata
            vaultUploadCalls += 1
        } else if (!current.hasSameContentForTest(metadata)) {
            error("FIXTURE metadata conflict")
        }
    }
    override suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata? = vaultMetadata
    override suspend fun listVaults(expectedUid: String): List<RemoteVaultData> = unsupported()

    override suspend fun uploadItem(
        expectedUid: String,
        vaultId: String,
        itemId: String,
        ciphertext: Ciphertext,
        metadata: RemoteItemMetadata
    ) {
        if (failUpload) error("FIXTURE network unavailable")
        onUpload?.invoke()
        val copied = Ciphertext.fromPersisted(ciphertext.bytes.copyOf())
        uploads += Upload(itemId, copied, metadata)
        items[itemId] = copied to metadata
    }

    override suspend fun replaceItemIfUnchanged(
        expectedUid: String,
        vaultId: String,
        expected: RemoteItemData?,
        replacement: RemoteItemData,
    ): Boolean {
        if (failUpload) error("FIXTURE network unavailable")
        beforeItemCas?.invoke()
        beforeItemCas = null
        val current = items[replacement.id]?.toRemoteItemData(replacement.id)
        if (current != expected) return false
        onUpload?.invoke()
        val ciphertext = Ciphertext.fromPersisted(replacement.ciphertext.copyOf())
        val metadata = RemoteItemMetadata(
            replacement.cryptoVersion,
            replacement.schemaVersion,
            replacement.revision,
            replacement.tombstone,
            replacement.createdAt,
            replacement.updatedAt,
        )
        uploads += Upload(replacement.id, ciphertext, metadata)
        items[replacement.id] = ciphertext to metadata
        return true
    }

    override suspend fun getItem(
        expectedUid: String,
        vaultId: String,
        itemId: String
    ): Pair<Ciphertext, RemoteItemMetadata>? {
        readOperations++
        return items[itemId]
    }

    override suspend fun listItems(expectedUid: String, vaultId: String): List<RemoteItemData> {
        readOperations++
        if (failList) error("FIXTURE network unavailable")
        return listOverride ?: items.mapNotNull { (id, pair) ->
            RemoteItemData(
                id = id,
                ciphertext = pair.first.bytes.copyOf(),
                cryptoVersion = pair.second.cryptoVersion,
                schemaVersion = pair.second.schemaVersion,
                revision = pair.second.revision,
                tombstone = pair.second.tombstone,
                createdAt = pair.second.createdAt,
                updatedAt = pair.second.updatedAt
            )
        }
    }

    override suspend fun listDeletedVaultIds(expectedUid: String): Set<String> = deletedVaultIds.toSet()

    override suspend fun purgeVault(expectedUid: String, vaultId: String, deletedAt: Long) = unsupported()
}

private fun unsupported(): Nothing = error("FIXTURE unsupported operation")

private fun Pair<Ciphertext, RemoteItemMetadata>.toRemoteItemData(itemId: String) = RemoteItemData(
    id = itemId,
    ciphertext = first.bytes.copyOf(),
    cryptoVersion = second.cryptoVersion,
    schemaVersion = second.schemaVersion,
    revision = second.revision,
    tombstone = second.tombstone,
    createdAt = second.createdAt,
    updatedAt = second.updatedAt,
)

private fun VaultMetaEntity.toRemoteMetadataForTest() = RemoteVaultMetadata(
    schemaVersion,
    cryptoVersion,
    kdfName,
    kdfMemoryKib,
    kdfIterations,
    kdfParallelism,
    kdfOutputLen,
    passwordSalt.copyOf(),
    passwordWrappedVdek.copyOf(),
    recoverySalt.copyOf(),
    recoveryWrappedVdek.copyOf(),
    passwordWrapEpoch,
    recoveryWrapEpoch,
    createdAt,
    updatedAt,
    metaRevision
)

@Suppress("CyclomaticComplexMethod")
private fun RemoteVaultMetadata.hasSameContentForTest(other: RemoteVaultMetadata): Boolean =
    schemaVersion == other.schemaVersion &&
        cryptoVersion == other.cryptoVersion &&
        kdfName == other.kdfName &&
        kdfMemoryKib == other.kdfMemoryKib &&
        kdfIterations == other.kdfIterations &&
        kdfParallelism == other.kdfParallelism &&
        kdfOutputLen == other.kdfOutputLen &&
        passwordSalt.contentEquals(other.passwordSalt) &&
        passwordWrappedVdek.contentEquals(other.passwordWrappedVdek) &&
        recoverySalt.contentEquals(other.recoverySalt) &&
        recoveryWrappedVdek.contentEquals(other.recoveryWrappedVdek) &&
        passwordWrapEpoch == other.passwordWrapEpoch &&
        recoveryWrapEpoch == other.recoveryWrapEpoch &&
        createdAt == other.createdAt &&
        updatedAt == other.updatedAt &&
        metaRevision == other.metaRevision

@Suppress("TooManyFunctions")
private class FakeEncryptedItemStore(
    private val dao: FakeEncryptedItemDao
) : EncryptedItemStore {
    private val items = linkedMapOf<String, StoredItem>()
    val pending = linkedMapOf<String, PendingConflict>()
    val watermarks = mutableMapOf<String, Int>()
    var lastPullAt: Long? = null

    fun seed(item: StoredItem) {
        items[item.itemId] = item.snapshot()
        dao.entities[item.itemId] = item.toEntity()
    }

    fun item(itemId: String): StoredItem? = items[itemId]?.snapshot()
    fun allItems(): List<StoredItem> = items.values.map { it.snapshot() }

    fun editRevision(itemId: String, revision: Int) {
        val current = requireNotNull(items[itemId])
        seed(current.copy(metadata = current.metadata.copy(revision = revision, dirty = true)))
    }

    override suspend fun put(itemId: String, ciphertext: Ciphertext, metadata: ItemLocalMetadata) {
        seed(StoredItem(itemId, ciphertext, metadata))
    }

    override suspend fun get(itemId: String): Pair<Ciphertext, ItemLocalMetadata>? =
        items[itemId]?.snapshot()?.let { it.ciphertext to it.metadata }

    override suspend fun delete(itemId: String) {
        items.remove(itemId)
        dao.entities.remove(itemId)
    }
    override suspend fun listActive(): List<StoredItem> = allItems().filterNot { it.metadata.tombstone }
    override suspend fun getDirtySnapshots(): List<StoredItem> =
        allItems().filter { it.metadata.dirty }

    override suspend fun getLastPullAt(): Long? = lastPullAt
    override suspend fun updateLastPullAt(timestamp: Long) { lastPullAt = timestamp }
    override suspend fun getMaxAcceptedRevision(itemId: String): Int =
        watermarks[itemId] ?: items[itemId]?.metadata?.lastSyncedRevision ?: 0

    override suspend fun markPushSucceeded(itemId: String, uploadedRevision: Int): Boolean {
        val current = items[itemId]
        val matches = current != null &&
            current.metadata.dirty &&
            current.metadata.revision == uploadedRevision
        if (matches) {
            val matched = checkNotNull(current)
            seed(
                matched.copy(
                    metadata = matched.metadata.copy(
                        dirty = false,
                        lastSyncedRevision = uploadedRevision
                    )
                )
            )
        }
        return matches
    }

    override suspend fun stageConflict(
        itemId: String,
        remoteItem: RemoteConflictItem,
        detectedAt: Long
    ) {
        val local = items[itemId] ?: return
        pending[itemId] = PendingConflict(itemId, detectedAt, remoteItem.revision)
        dao.entities[itemId] = local.toEntity(
            pendingRemoteCiphertext = remoteItem.ciphertext.copyOf(),
            pendingRemoteRevision = remoteItem.revision,
            pendingRemoteCryptoVersion = remoteItem.cryptoVersion,
            pendingRemoteSchemaVersion = remoteItem.schemaVersion,
            pendingRemoteTombstone = remoteItem.tombstone,
            pendingRemoteCreatedAt = remoteItem.createdAt,
            pendingRemoteUpdatedAt = remoteItem.updatedAt
        )
    }

    override suspend fun getPendingConflicts(): List<PendingConflict> = pending.values.toList()

    override suspend fun resolveAndInsertConflictCopy(data: ConflictResolutionData) {
        val remoteBytes = if (data.remoteMetadata.tombstone) byteArrayOf() else data.remoteCiphertext.bytes
        put(
            data.originalItemId,
            Ciphertext.fromPersisted(remoteBytes.copyOf()),
            ItemLocalMetadata(
                cryptoVersion = data.remoteMetadata.cryptoVersion,
                schemaVersion = data.remoteMetadata.schemaVersion,
                revision = data.remoteMetadata.revision,
                tombstone = data.remoteMetadata.tombstone,
                createdAt = data.remoteMetadata.createdAt,
                updatedAt = data.remoteMetadata.updatedAt,
                dirty = false,
                lastSyncedRevision = data.remoteMetadata.revision,
                conflictOf = null
            )
        )
        put(
            data.newItemId,
            data.localCiphertext,
            data.localMetadata.copy(dirty = true, lastSyncedRevision = 0, conflictOf = data.originalItemId)
        )
        pending.remove(data.originalItemId)
    }

    override suspend fun clearConflictStaging(itemId: String) { pending.remove(itemId) }
}

private class FakeEncryptedItemDao : EncryptedItemDao {
    val entities = linkedMapOf<String, EncryptedItemEntity>()
    override fun observeAllActive(): Flow<List<EncryptedItemEntity>> = flowOf(entities.values.toList())
    override suspend fun getAllActive(): List<EncryptedItemEntity> = entities.values.filterNot { it.tombstone }
    override suspend fun getAllItems(): List<EncryptedItemEntity> = entities.values.toList()
    override suspend fun getBackupSizeStats(): BackupSizeStats = BackupSizeStats(
        itemCount = entities.size.toLong(),
        ciphertextBytes = entities.values.sumOf { it.ciphertext.size.toLong() },
        maxCiphertextBytes = entities.values.maxOfOrNull { it.ciphertext.size }?.toLong() ?: 0,
    )
    override suspend fun countActive(): Int = entities.values.count { !it.tombstone }
    override suspend fun getById(itemId: String): EncryptedItemEntity? = entities[itemId]
    override fun observeById(itemId: String): Flow<EncryptedItemEntity?> = flowOf(entities[itemId])
    override suspend fun insertOrReplace(item: EncryptedItemEntity) { entities[item.itemId] = item }
    override suspend fun update(item: EncryptedItemEntity) { entities[item.itemId] = item }
    override suspend fun insertOrReplaceAll(items: List<EncryptedItemEntity>) {
        items.forEach { entities[it.itemId] = it }
    }
    override suspend fun deleteAll() { entities.clear() }
    override suspend fun getAllDirtyItems(): List<EncryptedItemEntity> = entities.values.filter { it.dirty }
    override suspend fun markPushSucceeded(itemId: String, uploadedRevision: Int): Int = 0
    override suspend fun getMaxAcceptedRevisionForItem(itemId: String): Int? =
        entities[itemId]?.lastSyncedRevision
}

private fun StoredItem.snapshot() = StoredItem(
    itemId = itemId,
    ciphertext = Ciphertext.fromPersisted(ciphertext.bytes.copyOf()),
    metadata = metadata.copy()
)

// El helper refleja el grupo indivisible de siete campos pendingRemote* de la entidad Room.
@Suppress("LongParameterList")
private fun StoredItem.toEntity(
    pendingRemoteCiphertext: ByteArray? = null,
    pendingRemoteRevision: Int? = null,
    pendingRemoteCryptoVersion: Int? = null,
    pendingRemoteSchemaVersion: Int? = null,
    pendingRemoteTombstone: Boolean? = null,
    pendingRemoteCreatedAt: Long? = null,
    pendingRemoteUpdatedAt: Long? = null
) = EncryptedItemEntity(
    itemId = itemId,
    ciphertext = ciphertext.bytes.copyOf(),
    cryptoVersion = metadata.cryptoVersion,
    schemaVersion = metadata.schemaVersion,
    revision = metadata.revision,
    tombstone = metadata.tombstone,
    createdAt = metadata.createdAt,
    updatedAt = metadata.updatedAt,
    dirty = metadata.dirty,
    lastSyncedRevision = metadata.lastSyncedRevision,
    conflictOf = metadata.conflictOf,
    pendingRemoteCiphertext = pendingRemoteCiphertext,
    pendingRemoteRevision = pendingRemoteRevision,
    pendingRemoteCryptoVersion = pendingRemoteCryptoVersion,
    pendingRemoteSchemaVersion = pendingRemoteSchemaVersion,
    pendingRemoteTombstone = pendingRemoteTombstone,
    pendingRemoteCreatedAt = pendingRemoteCreatedAt,
    pendingRemoteUpdatedAt = pendingRemoteUpdatedAt
)
