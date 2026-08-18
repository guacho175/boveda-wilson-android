package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.sync.backup.BackupFormat
import cl.bovedawilson.data.sync.backup.BackupSnapshot
import cl.bovedawilson.data.sync.session.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPublicationAuthorizerTest {
    @Test
    fun `grant is one use and bound to the same account snapshot session and VDEK`() = runBlocking {
        val password = "FIXTURE-PASSWORD".toCharArray()
        val created = VaultCrypto.createVault(VAULT_ID, password.copyOf()).orFail()
        val auth = FakeAuth(UID)
        val session = VaultSession()
        val authorizer = InMemoryBackupPublicationAuthorizer(auth, session)
        val snapshot = snapshot(created.vault)

        authorizer.authorize(snapshot, created.vault)
        unlock(session, created.vault)

        assertTrue(authorizer.isAuthorized(snapshot))
        assertTrue(authorizer.isAuthorized(snapshot))
        assertTrue(authorizer.consume(snapshot))
        assertFalse(authorizer.consume(snapshot))
        Wipe.chars(password)
    }

    @Test
    fun `lock invalidates grant even after unlocking again`() = runBlocking {
        val created = VaultCrypto.createVault(VAULT_ID, "FIXTURE-PASSWORD".toCharArray()).orFail()
        val session = VaultSession()
        val authorizer = InMemoryBackupPublicationAuthorizer(FakeAuth(UID), session)
        val snapshot = snapshot(created.vault)
        authorizer.authorize(snapshot, created.vault)
        session.lock()
        unlock(session, created.vault)

        assertFalse(authorizer.consume(snapshot))
    }

    @Test
    fun `grant structure never retains an unlocked vault`() {
        val grantClass = InMemoryBackupPublicationAuthorizer::class.java.declaredClasses
            .single { it.simpleName == "Grant" }

        assertFalse(
            grantClass.declaredFields.any {
                it.type == cl.bovedawilson.core.crypto.session.UnlockedVault::class.java
            },
        )
    }

    private fun unlock(
        session: VaultSession,
        vault: cl.bovedawilson.core.crypto.session.UnlockedVault,
    ) {
        val lease = requireNotNull(session.beginUnlock())
        check(session.tryUnlock(lease, vault, VAULT_ID))
    }

    private class FakeAuth(override var currentUserId: String?) : FirebaseAuthSource {
        override val isConfigured: Boolean = true
        override suspend fun signInWithEmail(email: String, password: CharArray): String = error("unused")
        override suspend fun signUpWithEmail(email: String, password: CharArray): String = error("unused")
        override suspend fun signInWithGoogleIdToken(idToken: String): String = error("unused")
        override suspend fun signOut() = Unit
    }

    private companion object {
        const val UID = "fixture-uid"
        const val VAULT_ID = "123e4567-e89b-42d3-a456-426614174001"
    }
}

private fun snapshot(vault: cl.bovedawilson.core.crypto.session.UnlockedVault): BackupSnapshot =
    BackupFormat.authenticate(unsignedSnapshot(), vault)

private fun unsignedSnapshot() = BackupSnapshot(
    magic = "bw-vault-backup",
    formatVersion = BackupFormat.FORMAT_VERSION,
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
    manifestAuthenticator = byteArrayOf(),
    items = emptyList(),
)

private fun <T> cl.bovedawilson.core.common.result.AppResult<T, *>.orFail(): T = when (this) {
    is cl.bovedawilson.core.common.result.AppResult.Success -> value
    is cl.bovedawilson.core.common.result.AppResult.Failure -> error("fixture failed")
}
