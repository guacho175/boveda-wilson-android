package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.RemoteVaultData
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultValidator
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.NoOpSyncScheduler
import cl.bovedawilson.data.sync.worker.SyncScheduler
import cl.bovedawilson.data.sync.worker.SyncWorkCanceller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class RemoteVaultOption(val id: String, val updatedAt: Long)

fun interface RemoteDeletionHandler {
    suspend fun deleteLocalCopy(expectedUid: String, vaultId: String): Boolean
}

private val NO_REMOTE_DELETION_HANDLER = RemoteDeletionHandler { _, _ -> false }

sealed class CloudLanding {
    data object LocalVault : CloudLanding()
    data object CreateVault : CloudLanding()
    data object LocalLinkRequired : CloudLanding()
    data object OwnerConflict : CloudLanding()

    class SelectVault(val options: List<RemoteVaultOption>) : CloudLanding() {
        init {
            require(options.size >= MIN_SELECTABLE_VAULTS)
        }
    }
}

/** Firebase solo identifica/autoriza y mueve envoltorios/ciphertext; nunca recibe plaintext. */
@Suppress("LongParameterList", "TooManyFunctions")
class CloudAccessRepository(
    private val auth: FirebaseAuthSource,
    private val remote: FirestoreVaultSource,
    private val metaStore: VaultMetaStore,
    private val session: VaultSession,
    private val dispatchers: AppDispatchers,
    private val syncWorkCanceller: SyncWorkCanceller,
    private val syncCoordinator: SyncCoordinator = SyncCoordinator(),
    private val syncScheduler: SyncScheduler = NoOpSyncScheduler,
    private val remoteDeletionHandler: RemoteDeletionHandler = NO_REMOTE_DELETION_HANDLER
) {
    private val operationMutex = Mutex()
    private var pendingSelection: PendingSelection? = null

    val isConfigured: Boolean get() = auth.isConfigured
    val isAuthenticated: Boolean get() = auth.currentUserId != null

    suspend fun hasLocalVault(): Boolean = withContext(dispatchers.io) { metaStore.getMeta() != null }

    suspend fun signIn(email: String, password: CharArray): AppResult<CloudLanding, AppError> =
        authenticate(password) { auth.signInWithEmail(email.trim(), it) }

    suspend fun signUp(email: String, password: CharArray): AppResult<CloudLanding, AppError> =
        authenticate(password) { auth.signUpWithEmail(email.trim(), it) }

    suspend fun signInWithGoogleIdToken(idToken: String): AppResult<CloudLanding, AppError> =
        withSerializedContext(dispatchers, operationMutex) {
            closedRemoteCall({ pendingSelection = null }) {
                pendingSelection = null
                val uid = auth.signInWithGoogleIdToken(idToken)
                if (auth.currentUserId != uid) {
                    AppResult.Failure(AppError.OperationFailed)
                } else {
                    resolveLanding(uid)
                }
            }
        }

    suspend fun resumeAuthenticatedSession(): AppResult<CloudLanding?, AppError> =
        withSerializedContext(dispatchers, operationMutex) {
            closedRemoteCall({ pendingSelection = null }) {
                pendingSelection = null
                val uid = if (auth.isConfigured) auth.currentUserId else null
                if (uid == null) {
                    AppResult.Success(null)
                } else {
                    resolveLanding(uid)
                }
            }
        }

    suspend fun selectRemoteVault(vaultId: String): AppResult<Unit, AppError> =
        withSerializedContext(dispatchers, operationMutex) {
            closedRemoteCall({ pendingSelection = null }) {
                val uid = auth.currentUserId
                    ?: return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                if (metaStore.getMeta() != null) {
                    pendingSelection = null
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }

                val pending = pendingSelection
                if (pending == null || pending.uid != uid) {
                    pendingSelection = null
                    session.lock()
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }
                val selected = pending.vaultsById[vaultId]
                    ?: return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                if (auth.currentUserId != uid) {
                    pendingSelection = null
                    session.lock()
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }

                metaStore.saveMeta(selected)
                pendingSelection = null
                syncScheduler.scheduleIfAuthorized()
                AppResult.Success(Unit)
            }
        }

    /**
     * Vincula una bóveda local solo tras un desbloqueo explícito. La escritura remota
     * ocurre antes del `ownerUid` local; un fallo o un cambio de cuenta/sesión deja el
     * propietario vacío y permite reintentar sin afirmar una vinculación inexistente.
     */
    suspend fun linkUnlockedLocalVault(): AppResult<CloudLanding, AppError> =
        withSerializedContext(dispatchers, operationMutex) {
            closedRemoteCall({ pendingSelection = null }) {
                pendingSelection = null
                val uid = auth.currentUserId
                    ?: return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                val local = metaStore.getMeta()
                    ?: return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                if (local.ownerUid.isNotEmpty()) {
                    if (local.ownerUid != uid) session.lock()
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }

                val unlockedState = session.state.value as? SessionState.Unlocked
                    ?: return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                val unlockedVault = session.getVault()
                    ?: return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                if (unlockedState.vaultId != local.vaultId) {
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }

                remote.createVaultMeta(uid, local.vaultId, local.toRemoteMetadata())

                val sameSession = session.state.value == unlockedState && session.getVault() === unlockedVault
                val currentLocal = metaStore.getMeta()
                val canCommitOwner = auth.currentUserId == uid &&
                    sameSession &&
                    currentLocal == local &&
                    currentLocal.ownerUid.isEmpty()
                if (!canCommitOwner) {
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }

                metaStore.saveMeta(currentLocal.copy(ownerUid = uid))
                syncScheduler.scheduleIfAuthorized()
                AppResult.Success(CloudLanding.LocalVault)
            }
        }

    suspend fun signOut(): AppResult<Unit, AppError> =
        withSerializedContext(dispatchers, operationMutex) {
            closedRemoteCall({ pendingSelection = null }) {
                pendingSelection = null
                session.lock()
                if (!syncWorkCanceller.cancelAndAwait()) {
                    return@closedRemoteCall AppResult.Failure(AppError.OperationFailed)
                }
                auth.signOut()
                AppResult.Success(Unit)
            }
        }

    private suspend fun authenticate(
        password: CharArray,
        authAction: suspend (CharArray) -> String
    ): AppResult<CloudLanding, AppError> = withSerializedContext(dispatchers, operationMutex) {
        try {
            closedRemoteCall({ pendingSelection = null }) {
                pendingSelection = null
                val uid = authAction(password)
                if (uid.isBlank() || auth.currentUserId != uid) {
                    session.lock()
                    AppResult.Failure(AppError.OperationFailed)
                } else {
                    resolveLanding(uid)
                }
            }
        } finally {
            // La fuente Auth también lo garantiza; esta segunda barrera cubre dobles defectuosos.
            Wipe.chars(password)
        }
    }

    private suspend fun resolveLanding(uid: String): AppResult<CloudLanding, AppError> {
        pendingSelection = null
        val result = if (uid.isBlank() || auth.currentUserId != uid) {
            session.lock()
            AppResult.Failure(AppError.OperationFailed)
        } else {
            val deletedVaultIds = remote.listDeletedVaultIds(uid)
            deletedVaultIds.forEach { deletedVaultId ->
                remote.purgeVault(uid, deletedVaultId, System.currentTimeMillis())
            }
            var local = metaStore.getMeta()
            if (local != null && local.vaultId in deletedVaultIds) {
                val deleted = remoteDeletionHandler.deleteLocalCopy(uid, local.vaultId)
                if (!deleted) return AppResult.Failure(AppError.OperationFailed)
                local = null
            }
            if (local != null) {
                local.resolveLanding(uid, session)
            } else {
                resolveRemoteLanding(uid)
            }
        }
        if (result is AppResult.Success && result.value is CloudLanding.LocalVault) {
            syncScheduler.scheduleIfAuthorized()
        }
        return result
    }

    private suspend fun resolveRemoteLanding(uid: String): AppResult<CloudLanding, AppError> {
        val remoteVaults = remote.listVaults(uid)
        val accountChanged = auth.currentUserId != uid
        if (accountChanged) session.lock()
        val parsed = if (accountChanged) {
            AppResult.Failure(AppError.OperationFailed)
        } else {
            remoteVaults.toLocalEntities(uid)
        }
        val entities = (parsed as? AppResult.Success)?.value
        return when {
            parsed is AppResult.Failure -> parsed
            entities == null -> AppResult.Failure(AppError.OperationFailed)
            entities.isEmpty() -> AppResult.Success(CloudLanding.CreateVault)
            entities.size == 1 -> adoptSingleRemoteVault(auth, metaStore, session, uid, entities.values.single())
            else -> {
                val options = remoteVaults.map { RemoteVaultOption(it.id, it.metadata.updatedAt) }
                pendingSelection = PendingSelection(uid, entities.toMap())
                AppResult.Success(CloudLanding.SelectVault(options))
            }
        }
    }

    private data class PendingSelection(
        val uid: String,
        val vaultsById: Map<String, VaultMetaEntity>
    )

    /** Serializa toda mutación Auth/Firestore contra SyncEngine y reduce excepciones a
     * categorías cerradas. La función miembro conserva los labels `return@closedRemoteCall`. */
    private suspend fun <T> closedRemoteCall(
        onFailure: () -> Unit,
        block: suspend () -> AppResult<T, AppError>
    ): AppResult<T, AppError> = syncCoordinator.exclusive {
        runClosedRemoteCall(onFailure, block)
    }
}

private fun VaultMetaEntity.toRemoteMetadata(): RemoteVaultMetadata = RemoteVaultMetadata(
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

private fun RemoteVaultData.toLocalEntity(ownerUid: String): VaultMetaEntity? {
    if (!RemoteVaultValidator.isValid(this)) return null
    val meta = metadata
    return VaultMetaEntity(
        vaultId = id,
        ownerUid = ownerUid,
        schemaVersion = meta.schemaVersion,
        cryptoVersion = meta.cryptoVersion,
        kdfName = meta.kdfName,
        kdfMemoryKib = meta.kdfMemoryKib,
        kdfIterations = meta.kdfIterations,
        kdfParallelism = meta.kdfParallelism,
        kdfOutputLen = meta.kdfOutputLen,
        passwordSalt = meta.passwordSalt.copyOf(),
        passwordWrappedVdek = meta.passwordWrappedVdek.copyOf(),
        recoverySalt = meta.recoverySalt.copyOf(),
        recoveryWrappedVdek = meta.recoveryWrappedVdek.copyOf(),
        passwordWrapEpoch = meta.passwordWrapEpoch,
        recoveryWrapEpoch = meta.recoveryWrapEpoch,
        createdAt = meta.createdAt,
        updatedAt = meta.updatedAt,
        metaRevision = meta.metaRevision
    )
}

private fun VaultMetaEntity.resolveLanding(
    authenticatedUid: String,
    session: VaultSession
): AppResult<CloudLanding, AppError> = when {
    ownerUid == authenticatedUid -> AppResult.Success(CloudLanding.LocalVault)
    ownerUid.isEmpty() -> AppResult.Success(CloudLanding.LocalLinkRequired)
    else -> {
        session.lock()
        AppResult.Success(CloudLanding.OwnerConflict)
    }
}

private fun List<RemoteVaultData>.toLocalEntities(
    ownerUid: String
): AppResult<LinkedHashMap<String, VaultMetaEntity>, AppError> {
    val entities = LinkedHashMap<String, VaultMetaEntity>(size)
    var valid = true
    for (remoteVault in this) {
        val entity = remoteVault.toLocalEntity(ownerUid)
        if (entity == null || entities.containsKey(remoteVault.id)) {
            valid = false
            break
        }
        entities[remoteVault.id] = entity
    }
    return if (valid) AppResult.Success(entities) else AppResult.Failure(AppError.MalformedInput)
}

private suspend fun adoptSingleRemoteVault(
    auth: FirebaseAuthSource,
    metaStore: VaultMetaStore,
    session: VaultSession,
    uid: String,
    entity: VaultMetaEntity
): AppResult<CloudLanding, AppError> = if (auth.currentUserId != uid || metaStore.getMeta() != null) {
    session.lock()
    AppResult.Failure(AppError.OperationFailed)
} else {
    metaStore.saveMeta(entity)
    AppResult.Success(CloudLanding.LocalVault)
}

/** Las causas de red/Auth nunca suben a UI ni se encadenan para evitar PII. */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private suspend fun <T> runClosedRemoteCall(
    onFailure: () -> Unit,
    block: suspend () -> AppResult<T, AppError>
): AppResult<T, AppError> {
    val result = try {
        block()
    } catch (e: CancellationException) {
        onFailure()
        throw e
    } catch (e: Exception) {
        AppResult.Failure(AppError.OperationFailed)
    }
    if (result is AppResult.Failure) onFailure()
    return result
}

private suspend fun <T> withSerializedContext(
    dispatchers: AppDispatchers,
    mutex: Mutex,
    block: suspend () -> T
): T = withContext(dispatchers.io) {
    mutex.lock()
    try {
        block()
    } finally {
        mutex.unlock()
    }
}

private const val MIN_SELECTABLE_VAULTS = 2
