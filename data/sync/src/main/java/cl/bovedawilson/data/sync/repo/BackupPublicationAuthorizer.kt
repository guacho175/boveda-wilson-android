package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.hash.Sha256
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.sync.backup.BackupFormat
import cl.bovedawilson.data.sync.backup.BackupSnapshot
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import java.util.Arrays
import java.util.UUID

interface BackupPublicationAuthorizer {
    fun authorize(snapshot: BackupSnapshot, restoredVault: UnlockedVault)
    fun isAuthorized(snapshot: BackupSnapshot): Boolean
    fun consume(snapshot: BackupSnapshot): Boolean
    fun clear()
}

/** Capacidad de un solo uso, solo en memoria y ligada a cuenta, sesión, snapshot y VDEK. */
class InMemoryBackupPublicationAuthorizer(
    private val auth: FirebaseAuthSource,
    private val session: VaultSession,
) : BackupPublicationAuthorizer {
    private var grant: Grant? = null

    @Synchronized
    override fun authorize(snapshot: BackupSnapshot, restoredVault: UnlockedVault) {
        clearLocked()
        if (!BackupFormat.isAuthentic(snapshot, restoredVault)) return
        val uid = auth.currentUserId ?: return
        val challengeId = UUID.randomUUID().toString()
        val challengeCiphertext = restoredVault.encrypt(
            CHALLENGE_PAYLOAD,
            AadBuilder.forItem(
                snapshot.vaultId,
                challengeId,
                SchemaVersion(snapshot.schemaVersion),
                CryptoVersion(snapshot.cryptoVersion),
            ),
        )
        grant = Grant(
            uid = uid,
            vaultId = snapshot.vaultId,
            generation = session.securityGeneration(),
            baselineHash = hash(snapshot),
            challenge = ProofChallenge(
                id = challengeId,
                ciphertext = challengeCiphertext,
                schemaVersion = snapshot.schemaVersion,
                cryptoVersion = snapshot.cryptoVersion,
            ),
        )
    }

    @Synchronized
    override fun isAuthorized(snapshot: BackupSnapshot): Boolean = grant?.let { validates(it, snapshot) } == true

    @Synchronized
    override fun consume(snapshot: BackupSnapshot): Boolean {
        val candidate = grant ?: return false
        grant = null
        val valid = validates(candidate, snapshot)
        Arrays.fill(candidate.baselineHash, 0)
        return valid
    }

    private fun validates(candidate: Grant, snapshot: BackupSnapshot): Boolean {
        val currentVault = session.getVault()
        val validContext = auth.currentUserId == candidate.uid &&
            snapshot.vaultId == candidate.vaultId &&
            session.securityGeneration() == candidate.generation &&
            session.state.value is SessionState.Unlocked &&
            currentVault != null &&
            Sha256.equals(candidate.baselineHash, hash(snapshot))
        return validContext &&
            BackupFormat.isAuthentic(snapshot, requireNotNull(currentVault)) &&
            currentVault.opens(candidate, snapshot.vaultId)
    }

    @Synchronized
    override fun clear() = clearLocked()

    private fun clearLocked() {
        grant?.baselineHash?.let { Arrays.fill(it, 0) }
        grant = null
    }

    private fun hash(snapshot: BackupSnapshot): ByteArray =
        Sha256.digest(BackupFormat.encode(snapshot))

    private class Grant(
        val uid: String,
        val vaultId: String,
        val generation: Long,
        val baselineHash: ByteArray,
        val challenge: ProofChallenge,
    )

    private class ProofChallenge(
        val id: String,
        val ciphertext: Ciphertext,
        val schemaVersion: Int,
        val cryptoVersion: Int,
    )

    private fun UnlockedVault.opens(grant: Grant, vaultId: String): Boolean = try {
        decrypt(
            grant.challenge.ciphertext,
            AadBuilder.forItem(
                vaultId,
                grant.challenge.id,
                SchemaVersion(grant.challenge.schemaVersion),
                CryptoVersion(grant.challenge.cryptoVersion),
            ),
        ) == CHALLENGE_PAYLOAD
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") _: Exception) {
        false
    }

    private companion object {
        val CHALLENGE_PAYLOAD = ItemPayload(
            v = 1,
            title = "backup-publication-proof",
            body = "",
            tags = emptyList(),
            fields = emptyList(),
            createdAt = 0,
            updatedAt = 0,
        )
    }
}
