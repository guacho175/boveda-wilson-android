package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.prefs.PendingVaultDeletion
import cl.bovedawilson.data.local.prefs.SettingsDataStore
import cl.bovedawilson.data.local.store.LocalVaultDataWiper
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.sync.biometric.BiometricUnlock
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import cl.bovedawilson.data.sync.mapper.VaultRecordMapper
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.SyncWorkCanceller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject

fun interface MasterPasswordVerifier {
    suspend fun verify(meta: VaultMetaEntity, password: CharArray): Boolean
}

fun interface BiometricKeyInvalidator {
    fun invalidate(): Boolean
}

class AndroidBiometricKeyInvalidator @Inject constructor(
    private val biometricUnlock: BiometricUnlock
) : BiometricKeyInvalidator {
    override fun invalidate(): Boolean = biometricUnlock.invalidateBiometricKey()
}

class VaultMasterPasswordVerifier @Inject constructor(
    private val dispatchers: AppDispatchers
) : MasterPasswordVerifier {
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override suspend fun verify(meta: VaultMetaEntity, password: CharArray): Boolean =
        withContext(dispatchers.default) {
            try {
                VaultCrypto.unlockWithPassword(VaultRecordMapper.toRecord(meta), password).fold(
                    onSuccess = { true },
                    onFailure = { false }
                )
            } catch (_: Exception) {
                false
            } finally {
                Wipe.chars(password)
            }
        }
}

/** Orquesta cierre de sesion y borrado terminal sin introducir plaintext en disco. */
@Suppress("LongParameterList")
class VaultLifecycleRepository(
    private val metaStore: VaultMetaStore,
    private val settings: SettingsDataStore,
    private val localWiper: LocalVaultDataWiper,
    private val remote: FirestoreVaultSource,
    private val auth: FirebaseAuthSource,
    private val session: VaultSession,
    private val biometricKeyInvalidator: BiometricKeyInvalidator,
    private val syncWorkCanceller: SyncWorkCanceller,
    private val masterPasswordVerifier: MasterPasswordVerifier,
    private val dispatchers: AppDispatchers,
    private val syncCoordinator: SyncCoordinator = SyncCoordinator()
) : RemoteDeletionHandler {
    private val operationMutex = Mutex()

    suspend fun hasPendingDeletion(): Boolean = settings.pendingVaultDeletion.first() != null

    suspend fun deleteVault(masterPassword: CharArray): AppResult<Unit, AppError> = serialized {
        try {
            session.lock()
            val meta = metaStore.getMeta()
                ?: return@serialized AppResult.Failure(AppError.OperationFailed)
            if (!masterPasswordVerifier.verify(meta, masterPassword)) {
                return@serialized AppResult.Failure(AppError.InvalidCredentials)
            }
            val requiresRemote = meta.ownerUid.isNotBlank()
            if (requiresRemote && auth.currentUserId != meta.ownerUid) {
                return@serialized AppResult.Failure(AppError.OperationFailed)
            }
            if (!syncWorkCanceller.cancelAndAwait()) {
                return@serialized AppResult.Failure(AppError.OperationFailed)
            }
            settings.markVaultDeletionPending(
                vaultId = meta.vaultId,
                requiresRemotePurge = requiresRemote,
                signOutAfterDeletion = requiresRemote
            )
            completePendingDeletion(
                PendingVaultDeletion(meta.vaultId, requiresRemote, requiresRemote)
            )
        } finally {
            Wipe.chars(masterPassword)
        }
    }

    /** Reanuda solo una operacion que el usuario ya confirmo y quedo journaled. */
    suspend fun resumePendingDeletion(): AppResult<Boolean, AppError> = serialized {
        val pending = settings.pendingVaultDeletion.first()
            ?: return@serialized AppResult.Success(false)
        session.lock()
        if (!syncWorkCanceller.cancelAndAwait()) {
            return@serialized AppResult.Failure(AppError.OperationFailed)
        }
        completePendingDeletion(pending).map { true }
    }

    /** Se invoca desde CloudAccessRepository mientras ya posee SyncCoordinator. El marker
     * remoto terminal equivale a una eliminación confirmada en otro cliente. */
    override suspend fun deleteLocalCopy(expectedUid: String, vaultId: String): Boolean = serialized {
        session.lock()
        if (!syncWorkCanceller.cancelAndAwait()) return@serialized false
        val meta = metaStore.getMeta() ?: return@serialized true
        val validIdentity = auth.currentUserId == expectedUid &&
            meta.vaultId == vaultId &&
            (meta.ownerUid.isEmpty() || meta.ownerUid == expectedUid)
        if (!validIdentity) return@serialized false
        // El marker remoto ya es terminal. Se journaliza la limpieza local antes de tocar
        // Room para poder reintentar la invalidación del alias si Keystore falla después.
        settings.markVaultDeletionPending(
            vaultId = vaultId,
            requiresRemotePurge = false,
            signOutAfterDeletion = false
        )
        localWiper.wipeAllVaultData()
        if (!biometricKeyInvalidator.invalidate()) return@serialized false
        settings.setBiometricEnabled(false)
        settings.clearVaultDeletionPending()
        true
    }

    private suspend fun completePendingDeletion(
        pending: PendingVaultDeletion
    ): AppResult<Unit, AppError> = syncCoordinator.exclusive {
        closedOperation {
            if (pending.requiresRemotePurge) {
                val meta = metaStore.getMeta()
                    ?: return@closedOperation AppResult.Failure(AppError.OperationFailed)
                val validOwner = meta.vaultId == pending.vaultId &&
                    meta.ownerUid.isNotBlank() &&
                    auth.currentUserId == meta.ownerUid
                if (!validOwner) return@closedOperation AppResult.Failure(AppError.OperationFailed)
                remote.purgeVault(meta.ownerUid, pending.vaultId, System.currentTimeMillis())
                settings.markRemotePurgeComplete()
            }

            localWiper.wipeAllVaultData()
            if (!biometricKeyInvalidator.invalidate()) {
                return@closedOperation AppResult.Failure(AppError.OperationFailed)
            }
            settings.setBiometricEnabled(false)
            if (pending.signOutAfterDeletion) auth.signOut()
            settings.clearVaultDeletionPending()
            AppResult.Success(Unit)
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T = withContext(dispatchers.io) {
        operationMutex.lock()
        try {
            block()
        } finally {
            operationMutex.unlock()
        }
    }
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private suspend fun <T> closedOperation(
    block: suspend () -> AppResult<T, AppError>
): AppResult<T, AppError> = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    AppResult.Failure(AppError.OperationFailed)
}
