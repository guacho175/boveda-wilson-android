package cl.bovedawilson.data.sync.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.prefs.SettingsDataStore
import cl.bovedawilson.data.local.store.LocalVaultDataWiper
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.RemoteItemData
import cl.bovedawilson.data.remote.firestore.RemoteItemMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultData
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.SyncWorkCanceller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultLifecycleRepositoryTest {
    @Test
    fun `wrong master password fails before journal or deletion and wipes input`() = runTest {
        val fixture = fixture(passwordValid = false)
        val password = "FIXTURE_wrong_password".toCharArray()

        val result = fixture.repository.deleteVault(password)

        assertSame(AppError.InvalidCredentials, result.failure())
        assertTrue(password.all { it == ' ' })
        assertEquals(0, fixture.remote.purgeCalls)
        assertEquals(0, fixture.wiper.calls)
        assertEquals(0, fixture.cancelCalls)
        assertNull(fixture.settings.pendingVaultDeletion.first())
        assertSame(SessionState.Locked, fixture.session.state.value)
    }

    @Test
    fun `linked deletion orders remote purge before local wipe and sign out`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(events = events)
        fixture.settings.setBiometricEnabled(true)
        val password = "FIXTURE_master_password".toCharArray()

        val result = fixture.repository.deleteVault(password)

        assertTrue(result is AppResult.Success)
        assertEquals(listOf("cancel", "purge", "wipe", "invalidate", "signOut"), events)
        assertEquals(VAULT_ID, fixture.remote.purgedVaultId)
        assertNull(fixture.store.meta)
        assertNull(fixture.settings.pendingVaultDeletion.first())
        assertFalse(fixture.settings.biometricEnabled.first())
        assertNull(fixture.auth.currentUserId)
        assertTrue(password.all { it == ' ' })
    }

    @Test
    fun `remote failure preserves journal and local ciphertext`() = runTest {
        val fixture = fixture(remoteFails = true)

        val result = fixture.repository.deleteVault("FIXTURE_master_password".toCharArray())

        assertSame(AppError.OperationFailed, result.failure())
        assertEquals(0, fixture.wiper.calls)
        assertEquals(VAULT_ID, fixture.store.meta?.vaultId)
        val pending = fixture.settings.pendingVaultDeletion.first()
        assertEquals(VAULT_ID, pending?.vaultId)
        assertTrue(pending?.requiresRemotePurge == true)
        assertTrue(pending?.signOutAfterDeletion == true)
    }

    @Test
    fun `resume after remote commit finishes local cleanup without a second purge`() = runTest {
        val fixture = fixture()
        fixture.settings.markVaultDeletionPending(VAULT_ID, false, true)

        val result = fixture.repository.resumePendingDeletion()

        assertEquals(true, (result as AppResult.Success).value)
        assertEquals(0, fixture.remote.purgeCalls)
        assertEquals(1, fixture.wiper.calls)
        assertNull(fixture.settings.pendingVaultDeletion.first())
        assertNull(fixture.auth.currentUserId)
    }

    @Test
    fun `owner mismatch fails closed before cancelling or journaling`() = runTest {
        val fixture = fixture(authUid = "fixture-other-owner")

        val result = fixture.repository.deleteVault("FIXTURE_master_password".toCharArray())

        assertSame(AppError.OperationFailed, result.failure())
        assertEquals(0, fixture.cancelCalls)
        assertEquals(0, fixture.remote.purgeCalls)
        assertNull(fixture.settings.pendingVaultDeletion.first())
        assertEquals(VAULT_ID, fixture.store.meta?.vaultId)
    }

    @Test
    fun `remote deletion journals local cleanup and retries keystore invalidation`() = runTest {
        val fixture = fixture(invalidationSucceeds = false)

        assertFalse(fixture.repository.deleteLocalCopy(OWNER_UID, VAULT_ID))

        assertNull(fixture.store.meta)
        assertEquals(VAULT_ID, fixture.settings.pendingVaultDeletion.first()?.vaultId)
        assertEquals(1, fixture.invalidator.calls)

        fixture.invalidator.succeeds = true
        val resumed = fixture.repository.resumePendingDeletion()

        assertEquals(true, (resumed as AppResult.Success).value)
        assertEquals(2, fixture.invalidator.calls)
        assertNull(fixture.settings.pendingVaultDeletion.first())
    }

    private fun fixture(
        passwordValid: Boolean = true,
        remoteFails: Boolean = false,
        authUid: String? = OWNER_UID,
        invalidationSucceeds: Boolean = true,
        events: MutableList<String> = mutableListOf()
    ): Fixture {
        val preferences = FakePreferencesDataStore()
        val settings = SettingsDataStore(preferences)
        val store = FakeVaultMetaStore(vaultMeta())
        val wiper = FakeLocalWiper(store, events)
        val remote = FakeRemote(events, remoteFails)
        val auth = FakeAuth(authUid, events)
        val session = VaultSession()
        val invalidator = FakeBiometricKeyInvalidator(invalidationSucceeds, events)
        var cancelCalls = 0
        val repository = VaultLifecycleRepository(
            metaStore = store,
            settings = settings,
            localWiper = wiper,
            remote = remote,
            auth = auth,
            session = session,
            biometricKeyInvalidator = invalidator,
            syncWorkCanceller = SyncWorkCanceller {
                cancelCalls++
                events += "cancel"
                true
            },
            masterPasswordVerifier = MasterPasswordVerifier { _, _ -> passwordValid },
            dispatchers = AppDispatchers(
                default = UnconfinedTestDispatcher(),
                io = UnconfinedTestDispatcher()
            )
        )
        return Fixture(repository, settings, store, wiper, remote, auth, session, invalidator) {
            cancelCalls
        }
    }

    private data class Fixture(
        val repository: VaultLifecycleRepository,
        val settings: SettingsDataStore,
        val store: FakeVaultMetaStore,
        val wiper: FakeLocalWiper,
        val remote: FakeRemote,
        val auth: FakeAuth,
        val session: VaultSession,
        val invalidator: FakeBiometricKeyInvalidator,
        val cancelCallCount: () -> Int
    ) {
        val cancelCalls: Int get() = cancelCallCount()
    }

    private class FakeBiometricKeyInvalidator(
        var succeeds: Boolean,
        private val events: MutableList<String>
    ) : BiometricKeyInvalidator {
        var calls = 0

        override fun invalidate(): Boolean {
            calls++
            events += "invalidate"
            return succeeds
        }
    }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FakeVaultMetaStore(initial: VaultMetaEntity?) : VaultMetaStore {
        private val state = MutableStateFlow(initial)
        var meta: VaultMetaEntity? = initial
            private set

        override suspend fun getMeta(): VaultMetaEntity? = meta
        override fun observeMeta(): Flow<VaultMetaEntity?> = state
        override suspend fun saveMeta(meta: VaultMetaEntity) {
            this.meta = meta
            state.value = meta
        }

        override suspend fun deleteAll() {
            meta = null
            state.value = null
        }
    }

    private class FakeLocalWiper(
        private val store: FakeVaultMetaStore,
        private val events: MutableList<String>
    ) : LocalVaultDataWiper {
        var calls = 0
        override suspend fun wipeAllVaultData() {
            calls++
            events += "wipe"
            store.deleteAll()
        }
    }

    private class FakeAuth(
        initialUid: String?,
        private val events: MutableList<String>
    ) : FirebaseAuthSource {
        override val isConfigured = true
        override var currentUserId: String? = initialUid
        override suspend fun signInWithEmail(email: String, password: CharArray): String = unsupported()
        override suspend fun signUpWithEmail(email: String, password: CharArray): String = unsupported()
        override suspend fun signInWithGoogleIdToken(idToken: String): String = unsupported()
        override suspend fun signOut() {
            events += "signOut"
            currentUserId = null
        }
    }

    private class FakeRemote(
        private val events: MutableList<String>,
        private val failPurge: Boolean
    ) : FirestoreVaultSource {
        var purgeCalls = 0
        var purgedVaultId: String? = null

        override suspend fun purgeVault(expectedUid: String, vaultId: String, deletedAt: Long) {
            purgeCalls++
            events += "purge"
            if (failPurge) error("FIXTURE remote unavailable")
            purgedVaultId = vaultId
        }

        override suspend fun createVaultMeta(
            expectedUid: String,
            vaultId: String,
            metadata: RemoteVaultMetadata
        ) = unsupported()
        override suspend fun updateVaultMeta(
            expectedUid: String,
            vaultId: String,
            metadata: RemoteVaultMetadata
        ) = unsupported()
        override suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata? = unsupported()
        override suspend fun listVaults(expectedUid: String): List<RemoteVaultData> = unsupported()
        override suspend fun uploadItem(
            expectedUid: String,
            vaultId: String,
            itemId: String,
            ciphertext: Ciphertext,
            metadata: RemoteItemMetadata
        ) = unsupported()

        override suspend fun getItem(
            expectedUid: String,
            vaultId: String,
            itemId: String
        ): Pair<Ciphertext, RemoteItemMetadata>? = unsupported()

        override suspend fun listItems(expectedUid: String, vaultId: String): List<RemoteItemData> = unsupported()
        override suspend fun listDeletedVaultIds(expectedUid: String): Set<String> = emptySet()
    }

    private fun vaultMeta() = VaultMetaEntity(
        VAULT_ID,
        OWNER_UID,
        1,
        1,
        "argon2id",
        65_536,
        3,
        4,
        32,
        ByteArray(16),
        ByteArray(48),
        ByteArray(32),
        ByteArray(48),
        1,
        1,
        10L,
        10L,
        1
    )

    private fun <T> AppResult<T, AppError>.failure(): AppError = (this as AppResult.Failure).error

    private companion object {
        const val VAULT_ID = "11111111-1111-4111-8111-111111111111"
        const val OWNER_UID = "fixture-owner"
    }
}

private fun unsupported(): Nothing = error("FIXTURE unsupported operation")
