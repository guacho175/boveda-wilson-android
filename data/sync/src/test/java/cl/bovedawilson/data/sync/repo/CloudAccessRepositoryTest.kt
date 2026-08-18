package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.RemoteItemData
import cl.bovedawilson.data.remote.firestore.RemoteItemMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultData
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.SyncScheduler
import cl.bovedawilson.data.sync.worker.SyncWorkCanceller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudAccessRepositoryTest {

    @Test
    fun `google id token fixture resolves landing without touching passwords`() = runTest {
        val auth = FakeAuthSource()
        val remote = FakeFirestoreVaultSource()

        val landing = repository(auth, remote, FakeVaultMetaStore(), VaultSession())
            .signInWithGoogleIdToken(FIXTURE_GOOGLE_ID_TOKEN)
            .successValue()

        assertSame(CloudLanding.CreateVault, landing)
        assertTrue(auth.receivedExpectedGoogleToken)
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun `cero remotas ofrece crear y limpia la password consumida`() = runTest {
        val auth = FakeAuthSource()
        val remote = FakeFirestoreVaultSource()
        val store = FakeVaultMetaStore()
        val repository = repository(auth, remote, store, VaultSession())
        val password = FIXTURE_PASSWORD.copyOf()

        val landing = repository.signIn(" person@example.invalid ", password).successValue()

        assertSame(CloudLanding.CreateVault, landing)
        assertEquals(1, remote.listCalls)
        assertEquals("person@example.invalid", auth.lastEmail)
        assertTrue(password.all { it == ' ' })
        assertTrue(auth.receivedExpectedPassword)
    }

    @Test
    fun `una remota valida se adopta automaticamente con owner local`() = runTest {
        val auth = FakeAuthSource()
        val remote = FakeFirestoreVaultSource(vaults = mutableListOf(remoteVault(VAULT_A)))
        val store = FakeVaultMetaStore()
        val repository = repository(auth, remote, store, VaultSession())

        val landing = repository.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()

        assertSame(CloudLanding.LocalVault, landing)
        assertEquals(UID_A, store.meta?.ownerUid)
        assertEquals(VAULT_A, store.meta?.vaultId)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun `marcador remoto terminal se reanuda antes de ofrecer crear`() = runTest {
        val auth = FakeAuthSource()
        val remote = FakeFirestoreVaultSource(
            vaults = mutableListOf(remoteVault(VAULT_A)),
            deletedVaultIds = mutableSetOf(VAULT_A)
        )
        val store = FakeVaultMetaStore()

        val landing = repository(auth, remote, store, VaultSession())
            .signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf())
            .successValue()

        assertSame(CloudLanding.CreateVault, landing)
        assertEquals(1, remote.purgeCalls)
        assertNull(store.meta)
    }

    @Test
    fun `marcador de otro cliente elimina la copia local antes del landing`() = runTest {
        val handler = FakeRemoteDeletionHandler()
        val remote = FakeFirestoreVaultSource(deletedVaultIds = mutableSetOf(VAULT_A))
        val store = FakeVaultMetaStore(localMeta(VAULT_A, UID_A))
        val repository = repository(
            FakeAuthSource(),
            remote,
            store,
            VaultSession(),
            deletionHandler = handler
        )

        val landing = repository.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()

        assertSame(CloudLanding.CreateVault, landing)
        assertEquals(listOf(UID_A to VAULT_A), handler.calls)
    }

    @Test
    fun `owner local autorizado programa sincronizacion periodica`() = runTest {
        val scheduler = FakeSyncScheduler()
        val repository = repository(
            FakeAuthSource(),
            FakeFirestoreVaultSource(),
            FakeVaultMetaStore(localMeta(VAULT_A, UID_A)),
            VaultSession(),
            scheduler
        )

        assertSame(
            CloudLanding.LocalVault,
            repository.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()
        )
        assertEquals(1, scheduler.periodicCalls)
    }

    @Test
    fun `multiples remotas exigen seleccion ofrecida sin volver a listar`() = runTest {
        val auth = FakeAuthSource()
        val remote = FakeFirestoreVaultSource(
            vaults = mutableListOf(remoteVault(VAULT_A), remoteVault(VAULT_B))
        )
        val store = FakeVaultMetaStore()
        val repository = repository(auth, remote, store, VaultSession())

        val landing = repository.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()
        val selection = landing as CloudLanding.SelectVault
        assertEquals(listOf(VAULT_A, VAULT_B), selection.options.map { it.id })
        assertNull(store.meta)

        repository.selectRemoteVault(VAULT_B).successValue()

        assertEquals(1, remote.listCalls)
        assertEquals(VAULT_B, store.meta?.vaultId)
        assertEquals(UID_A, store.meta?.ownerUid)
    }

    @Test
    fun `seleccion pendiente queda ligada al uid y se consume al cambiar la sesion`() = runTest {
        val auth = FakeAuthSource()
        val remote = FakeFirestoreVaultSource(
            vaults = mutableListOf(remoteVault(VAULT_A), remoteVault(VAULT_B))
        )
        val store = FakeVaultMetaStore()
        val repository = repository(auth, remote, store, VaultSession())
        repository.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()

        auth.currentUid = UID_B
        assertSame(AppError.OperationFailed, repository.selectRemoteVault(VAULT_A).failureValue())
        auth.currentUid = UID_A
        assertSame(AppError.OperationFailed, repository.selectRemoteVault(VAULT_A).failureValue())

        assertEquals(1, remote.listCalls)
        assertNull(store.meta)
    }

    @Test
    fun `owner igual vacio y distinto producen estados cerrados sin tocar remoto`() = runTest {
        val remoteSame = FakeFirestoreVaultSource()
        val storeSame = FakeVaultMetaStore(localMeta(VAULT_A, UID_A))
        val same = repository(FakeAuthSource(), remoteSame, storeSame, VaultSession())
        assertSame(CloudLanding.LocalVault, same.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue())

        val remoteEmpty = FakeFirestoreVaultSource()
        val storeEmpty = FakeVaultMetaStore(localMeta(VAULT_A, ""))
        val empty = repository(FakeAuthSource(), remoteEmpty, storeEmpty, VaultSession())
        assertSame(
            CloudLanding.LocalLinkRequired,
            empty.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()
        )

        val remoteConflict = FakeFirestoreVaultSource()
        val storeConflict = FakeVaultMetaStore(localMeta(VAULT_A, UID_B))
        val conflict = repository(FakeAuthSource(), remoteConflict, storeConflict, VaultSession())
        assertSame(
            CloudLanding.OwnerConflict,
            conflict.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).successValue()
        )

        listOf(remoteSame, remoteEmpty, remoteConflict).forEach {
            assertEquals(0, it.listCalls)
            assertEquals(0, it.uploadCalls)
        }
        assertEquals(0, storeSame.saveCalls)
        assertEquals(0, storeEmpty.saveCalls)
        assertEquals(0, storeConflict.saveCalls)
    }

    @Test
    fun `metadata remota invalida falla antes de persistir`() = runTest {
        val invalid = remoteVault(VAULT_A).let {
            RemoteVaultData(it.id, it.metadata.copyForTest(kdfMemoryKib = 1))
        }
        val store = FakeVaultMetaStore()
        val repository = repository(
            FakeAuthSource(),
            FakeFirestoreVaultSource(vaults = mutableListOf(invalid)),
            store,
            VaultSession()
        )

        assertSame(
            AppError.MalformedInput,
            repository.signIn(FIXTURE_EMAIL, FIXTURE_PASSWORD.copyOf()).failureValue()
        )
        assertNull(store.meta)
    }

    @Test
    fun `error Auth se reduce a categoria generica y limpia password`() = runTest {
        val auth = FakeAuthSource(failAuthentication = true)
        val repository = repository(auth, FakeFirestoreVaultSource(), FakeVaultMetaStore(), VaultSession())
        val password = FIXTURE_PASSWORD.copyOf()

        val error = repository.signIn(FIXTURE_EMAIL, password).failureValue()

        assertSame(AppError.OperationFailed, error)
        assertTrue(password.all { it == ' ' })
    }

    @Test
    fun `reanudar sin configuracion o sin usuario no consulta remoto`() = runTest {
        val offlineAuth = FakeAuthSource(isConfigured = false, initialUid = null)
        val offlineRemote = FakeFirestoreVaultSource()
        val offline = repository(offlineAuth, offlineRemote, FakeVaultMetaStore(), VaultSession())
        assertNull(offline.resumeAuthenticatedSession().successValue())

        val signedOutAuth = FakeAuthSource(initialUid = null)
        val signedOutRemote = FakeFirestoreVaultSource()
        val signedOut = repository(signedOutAuth, signedOutRemote, FakeVaultMetaStore(), VaultSession())
        assertNull(signedOut.resumeAuthenticatedSession().successValue())

        assertEquals(0, offlineRemote.listCalls)
        assertEquals(0, signedOutRemote.listCalls)
    }

    @Test
    fun `conflicto vinculo explicito y signOut respetan bloqueo y orden`() = runTest {
        val created = VaultCrypto.createVault(VAULT_A, FIXTURE_PASSWORD.copyOf()).successValue()

        val conflictSession = VaultSession().apply { unlockForTest(created.vault, VAULT_A, 1L) }
        val conflictRemote = FakeFirestoreVaultSource()
        val conflictRepository = repository(
            FakeAuthSource(initialUid = UID_A),
            conflictRemote,
            FakeVaultMetaStore(localMeta(VAULT_A, UID_B)),
            conflictSession
        )
        assertSame(CloudLanding.OwnerConflict, conflictRepository.resumeAuthenticatedSession().successValue())
        assertSame(SessionState.Locked, conflictSession.state.value)
        assertEquals(0, conflictRemote.listCalls)
        assertEquals(0, conflictRemote.uploadCalls)

        val events = mutableListOf<String>()
        val linkSession = VaultSession().apply { unlockForTest(created.vault, VAULT_A, 2L) }
        val auth = FakeAuthSource(initialUid = UID_A, events = events)
        val remote = FakeFirestoreVaultSource(events = events)
        val originalMeta = localMeta(VAULT_A, "")
        val store = FakeVaultMetaStore(originalMeta)
        val repository = repository(auth, remote, store, linkSession)

        assertSame(CloudLanding.LocalVault, repository.linkUnlockedLocalVault().successValue())
        assertEquals(UID_A, store.meta?.ownerUid)
        auth.beforeSignOut = { assertSame(SessionState.Locked, linkSession.state.value) }
        repository.signOut().successValue()

        assertEquals(listOf("upload", "signOut"), events)
        assertSame(SessionState.Locked, linkSession.state.value)
        assertEquals(VAULT_A, store.meta?.vaultId)
        assertTrue(originalMeta.passwordWrappedVdek.contentEquals(store.meta!!.passwordWrappedVdek))

        val changedSession = VaultSession().apply { unlockForTest(created.vault, VAULT_A, 3L) }
        val changedAuth = FakeAuthSource(initialUid = UID_A)
        val changedRemote = FakeFirestoreVaultSource().apply {
            afterUpload = { changedAuth.currentUid = UID_B }
        }
        val changedStore = FakeVaultMetaStore(localMeta(VAULT_A, ""))
        val changedRepository = repository(changedAuth, changedRemote, changedStore, changedSession)

        assertSame(AppError.OperationFailed, changedRepository.linkUnlockedLocalVault().failureValue())
        assertEquals("", changedStore.meta?.ownerUid)
        assertEquals(0, changedStore.saveCalls)
    }

    @Suppress("LongParameterList")
    private fun repository(
        auth: FakeAuthSource,
        remote: FakeFirestoreVaultSource,
        store: FakeVaultMetaStore,
        session: VaultSession,
        scheduler: SyncScheduler? = null,
        deletionHandler: RemoteDeletionHandler? = null
    ): CloudAccessRepository = CloudAccessRepository(
        auth = auth,
        remote = remote,
        metaStore = store,
        session = session,
        dispatchers = AppDispatchers(io = UnconfinedTestDispatcher()),
        syncWorkCanceller = SyncWorkCanceller { true },
        syncScheduler = scheduler ?: FakeSyncScheduler(),
        remoteDeletionHandler = deletionHandler ?: RemoteDeletionHandler { _, _ -> false }
    )

    private class FakeAuthSource(
        override val isConfigured: Boolean = true,
        initialUid: String? = null,
        private val failAuthentication: Boolean = false,
        private val events: MutableList<String> = mutableListOf()
    ) : FirebaseAuthSource {
        var currentUid: String? = initialUid
        var lastEmail: String? = null
        var receivedExpectedPassword: Boolean = false
        var receivedExpectedGoogleToken: Boolean = false
        var beforeSignOut: () -> Unit = {}

        override val currentUserId: String? get() = currentUid

        override suspend fun signInWithEmail(email: String, password: CharArray): String = authenticate(email, password)

        override suspend fun signUpWithEmail(email: String, password: CharArray): String = authenticate(email, password)

        override suspend fun signInWithGoogleIdToken(idToken: String): String {
            receivedExpectedGoogleToken = idToken == FIXTURE_GOOGLE_ID_TOKEN
            currentUid = UID_A
            return UID_A
        }

        override suspend fun signOut() {
            beforeSignOut()
            events += "signOut"
            currentUid = null
        }

        private fun authenticate(email: String, password: CharArray): String = try {
            lastEmail = email
            receivedExpectedPassword = password.contentEquals(FIXTURE_PASSWORD)
            if (failAuthentication) error("raw_provider_detail_must_not_escape")
            currentUid = UID_A
            UID_A
        } finally {
            Wipe.chars(password)
        }
    }

    private class FakeFirestoreVaultSource(
        val vaults: MutableList<RemoteVaultData> = mutableListOf(),
        private val deletedVaultIds: MutableSet<String> = mutableSetOf(),
        private val events: MutableList<String> = mutableListOf()
    ) : FirestoreVaultSource {
        var listCalls: Int = 0
        var uploadCalls: Int = 0
        var purgeCalls: Int = 0
        var afterUpload: () -> Unit = {}

        override suspend fun createVaultMeta(
            expectedUid: String,
            vaultId: String,
            metadata: RemoteVaultMetadata
        ) {
            uploadCalls += 1
            events += "upload"
            afterUpload()
        }

        override suspend fun updateVaultMeta(
            expectedUid: String,
            vaultId: String,
            metadata: RemoteVaultMetadata
        ) = error("unexpected_update_vault")

        override suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata? =
            vaults.firstOrNull { it.id == vaultId }?.metadata

        override suspend fun listVaults(expectedUid: String): List<RemoteVaultData> {
            listCalls += 1
            return vaults.toList()
        }

        override suspend fun uploadItem(
            expectedUid: String,
            vaultId: String,
            itemId: String,
            ciphertext: Ciphertext,
            metadata: RemoteItemMetadata
        ) = error("unexpected_upload_item")

        override suspend fun getItem(
            expectedUid: String,
            vaultId: String,
            itemId: String
        ): Pair<Ciphertext, RemoteItemMetadata>? = error("unexpected_get_item")

        override suspend fun listItems(expectedUid: String, vaultId: String): List<RemoteItemData> =
            error("unexpected_list_items")

        override suspend fun listDeletedVaultIds(expectedUid: String): Set<String> = deletedVaultIds.toSet()

        override suspend fun purgeVault(expectedUid: String, vaultId: String, deletedAt: Long) {
            purgeCalls += 1
            vaults.removeAll { it.id == vaultId }
        }
    }

    private class FakeSyncScheduler : SyncScheduler {
        var periodicCalls = 0
        var immediateCalls = 0
        override suspend fun scheduleIfAuthorized() {
            periodicCalls += 1
        }
        override suspend fun syncNowIfAuthorized() {
            immediateCalls += 1
        }
    }

    private class FakeRemoteDeletionHandler : RemoteDeletionHandler {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun deleteLocalCopy(expectedUid: String, vaultId: String): Boolean {
            calls += expectedUid to vaultId
            return true
        }
    }

    private class FakeVaultMetaStore(initial: VaultMetaEntity? = null) : VaultMetaStore {
        private val state = MutableStateFlow(initial)
        var meta: VaultMetaEntity? = initial
            private set
        var saveCalls: Int = 0
            private set

        override suspend fun getMeta(): VaultMetaEntity? = meta
        override fun observeMeta(): Flow<VaultMetaEntity?> = state

        override suspend fun saveMeta(meta: VaultMetaEntity) {
            saveCalls += 1
            this.meta = meta
            state.value = meta
        }

        override suspend fun deleteAll() {
            meta = null
            state.value = null
        }
    }

    private companion object {
        const val UID_A = "uid-a"
        const val UID_B = "uid-b"
        const val FIXTURE_EMAIL = "person@example.invalid"
        const val FIXTURE_GOOGLE_ID_TOKEN = "FIXTURE_GOOGLE_ID_TOKEN_NOT_REAL"
        const val VAULT_A = "11111111-1111-4111-8111-111111111111"
        const val VAULT_B = "22222222-2222-4222-8222-222222222222"
        val FIXTURE_PASSWORD = "FIXTURE_password_1234".toCharArray() // valor ficticio
    }
}

private fun VaultSession.unlockForTest(
    vault: cl.bovedawilson.core.crypto.session.UnlockedVault,
    vaultId: String,
    openedAt: Long
) {
    val lease = requireNotNull(beginUnlock())
    check(tryUnlock(lease, vault, vaultId, openedAt))
}

private fun remoteVault(id: String): RemoteVaultData = RemoteVaultData(id, remoteMetadata())

private fun remoteMetadata(): RemoteVaultMetadata {
    val now = System.currentTimeMillis()
    return RemoteVaultMetadata(
        schemaVersion = 1,
        cryptoVersion = 1,
        kdfName = "argon2id",
        kdfMemoryKib = 65_536,
        kdfIterations = 3,
        kdfParallelism = 4,
        kdfOutputLen = 32,
        passwordSalt = ByteArray(16) { it.toByte() }, // fixture ficticio
        passwordWrappedVdek = ByteArray(48) { 1 }, // fixture ficticio
        recoverySalt = ByteArray(32) { it.toByte() }, // fixture ficticio
        recoveryWrappedVdek = ByteArray(48) { 2 }, // fixture ficticio
        passwordWrapEpoch = 1,
        recoveryWrapEpoch = 1,
        createdAt = now,
        updatedAt = now,
        metaRevision = 1
    )
}

private fun localMeta(vaultId: String, ownerUid: String): VaultMetaEntity {
    val remote = remoteMetadata()
    return VaultMetaEntity(
        vaultId = vaultId,
        ownerUid = ownerUid,
        schemaVersion = remote.schemaVersion,
        cryptoVersion = remote.cryptoVersion,
        kdfName = remote.kdfName,
        kdfMemoryKib = remote.kdfMemoryKib,
        kdfIterations = remote.kdfIterations,
        kdfParallelism = remote.kdfParallelism,
        kdfOutputLen = remote.kdfOutputLen,
        passwordSalt = remote.passwordSalt,
        passwordWrappedVdek = remote.passwordWrappedVdek,
        recoverySalt = remote.recoverySalt,
        recoveryWrappedVdek = remote.recoveryWrappedVdek,
        passwordWrapEpoch = remote.passwordWrapEpoch,
        recoveryWrapEpoch = remote.recoveryWrapEpoch,
        createdAt = remote.createdAt,
        updatedAt = remote.updatedAt,
        metaRevision = remote.metaRevision
    )
}

private fun RemoteVaultMetadata.copyForTest(kdfMemoryKib: Int): RemoteVaultMetadata = RemoteVaultMetadata(
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
    metaRevision = metaRevision
)

private fun <T, E> AppResult<T, E>.successValue(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> error("Expected success")
}

private fun <T, E> AppResult<T, E>.failureValue(): E = when (this) {
    is AppResult.Success -> error("Expected failure")
    is AppResult.Failure -> error
}
