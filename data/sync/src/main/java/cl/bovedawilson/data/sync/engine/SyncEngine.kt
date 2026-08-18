package cl.bovedawilson.data.sync.engine

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.mapper.CiphertextMapper
import cl.bovedawilson.data.local.store.EncryptedItemStore
import cl.bovedawilson.data.local.store.ItemLocalMetadata
import cl.bovedawilson.data.local.store.StoredItem
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.MalformedRemoteDataException
import cl.bovedawilson.data.remote.firestore.RemoteItemData
import cl.bovedawilson.data.remote.firestore.RemoteItemMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultValidator
import cl.bovedawilson.data.sync.dto.RemoteItemDto
import kotlinx.coroutines.CancellationException

/**
 * Sincroniza exclusivamente ciphertext. La identidad remota se toma de Firebase Auth y
 * se cruza con el propietario local antes de tocar Firestore. Ninguna operación de push
 * o pull necesita que la sesión criptográfica esté abierta; solo la resolución inmediata
 * de un conflicto puede recifrar una copia local.
 */
// El motor mantiene juntas las funciones pequeñas que implementan un único ciclo de protocolo.
@Suppress("LongParameterList", "SwallowedException", "ThrowsCount", "TooManyFunctions")
class SyncEngine(
    private val localStore: EncryptedItemStore,
    private val remoteSource: FirestoreVaultSource,
    private val vaultMetaStore: VaultMetaStore,
    private val authSource: FirebaseAuthSource,
    private val conflictResolver: ConflictResolver,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val coordinator: SyncCoordinator = SyncCoordinator()
) {
    /** Ejecuta pull y push. El resultado solo es exitoso si ambas fases terminan sin error. */
    suspend fun sync(): SyncResult = coordinator.exclusive { syncExclusive() }

    private suspend fun syncExclusive(): SyncResult = try {
        val authorized = requireAuthorizedVault()
        if (authorized.vaultId in listDeletedVaultIds(authorized.uid)) {
            throw SyncException.Unauthorized()
        }
        syncVaultMetadata(authorized)
        val pullResult = pull(authorized.uid, authorized.vaultId)
        val pushResult = push(authorized.uid, authorized.vaultId, pullResult.conflictedItems)
        SyncResult().apply {
            merge(pullResult)
            merge(pushResult)
            success = pullResult.success && pushResult.success && errors.isEmpty()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncException) {
        SyncResult().record(e)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        SyncResult().record(SyncException.Operational())
    }

    private suspend fun requireAuthorizedVault(): AuthorizedVault {
        val meta = vaultMetaStore.getMeta() ?: throw SyncException.Unauthorized()
        val currentUid = authSource.currentUserId
        val isAuthorized = meta.vaultId.isNotBlank() &&
            meta.ownerUid.isNotBlank() &&
            !currentUid.isNullOrBlank() &&
            meta.ownerUid == currentUid
        if (!isAuthorized) {
            throw SyncException.Unauthorized()
        }
        return AuthorizedVault(checkNotNull(currentUid), meta)
    }

    private suspend fun syncVaultMetadata(authorized: AuthorizedVault) {
        val local = authorized.meta
        val localRemote = local.toRemoteMetadata()
        val remote = try {
            remoteSource.getVaultMeta(authorized.uid, local.vaultId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: MalformedRemoteDataException) {
            throw SyncException.Protocol()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw SyncException.Network()
        }
        when {
            remote == null -> createVaultMetadata(authorized.uid, local.vaultId, localRemote)
            RemoteVaultValidator.hasSameContent(localRemote, remote) -> Unit
            local.metaRevision > remote.metaRevision -> {
                requireValidMetadataTransition(local.vaultId, remote, localRemote)
                updateVaultMetadata(authorized.uid, local.vaultId, remote, localRemote)
            }
            remote.metaRevision > local.metaRevision -> {
                requireValidMetadataTransition(local.vaultId, localRemote, remote)
                val replacement = remote.toLocalEntity(local.vaultId, local.ownerUid)
                if (!vaultMetaStore.replaceIfUnchanged(local, replacement)) {
                    throw SyncException.Operational()
                }
            }
            else -> throw SyncException.Protocol()
        }
    }

    private suspend fun createVaultMetadata(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ) {
        try {
            remoteSource.createVaultMeta(expectedUid, vaultId, metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: MalformedRemoteDataException) {
            throw SyncException.Protocol()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw SyncException.Network()
        }
    }

    private suspend fun updateVaultMetadata(
        expectedUid: String,
        vaultId: String,
        expected: RemoteVaultMetadata,
        metadata: RemoteVaultMetadata
    ) {
        try {
            if (!remoteSource.replaceVaultMetaIfUnchanged(expectedUid, vaultId, expected, metadata)) {
                throw SyncException.Protocol()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: MalformedRemoteDataException) {
            throw SyncException.Protocol()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw SyncException.Network()
        }
    }

    private fun requireValidMetadataTransition(
        vaultId: String,
        current: RemoteVaultMetadata,
        candidate: RemoteVaultMetadata
    ) {
        try {
            RemoteVaultValidator.requireValidTransition(vaultId, current, candidate, nowMillis())
        } catch (_: MalformedRemoteDataException) {
            throw SyncException.Protocol()
        }
    }

    private suspend fun listDeletedVaultIds(expectedUid: String): Set<String> = try {
        remoteSource.listDeletedVaultIds(expectedUid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: MalformedRemoteDataException) {
        throw SyncException.Protocol()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        throw SyncException.Network()
    }

    private suspend fun pull(expectedUid: String, vaultId: String): SyncResult {
        val result = SyncResult()
        return try {
            // Escaneo completo autoritativo: updatedAt es reloj de cliente y no puede ser
            // un cursor exclusivo sin abrir ventanas permanentes de pérdida.
            val remoteItems = listRemoteItems(expectedUid, vaultId)
            remoteItems.forEach { processPulledItem(it.toDto(), result, vaultId) }

            val completedAt = nowMillis()
            localStore.updateLastPullAt(completedAt)
            result.lastPullAt = completedAt
            result.success = true
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            result.record(e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            result.record(SyncException.Operational())
        }
    }

    private suspend fun listRemoteItems(expectedUid: String, vaultId: String): List<RemoteItemData> =
        try {
            remoteSource.listItems(expectedUid, vaultId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: MalformedRemoteDataException) {
            throw SyncException.Protocol()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw SyncException.Network()
        }

    private suspend fun processPulledItem(remote: RemoteItemDto, result: SyncResult, vaultId: String) {
        val normalized = validateAndNormalizeRemote(remote)
        val watermark = localStore.getMaxAcceptedRevision(normalized.id)
        if (normalized.revision < watermark) throw SyncException.Rollback()

        val local = localStore.get(normalized.id)
        when {
            local == null -> {
                localStore.putRemote(normalized)
                result.itemsPulled++
            }

            !local.second.dirty && normalized.revision > local.second.revision -> {
                localStore.putRemote(normalized)
                result.itemsUpdated++
            }

            local.second.dirty && normalized.revision > local.second.lastSyncedRevision -> {
                conflictResolver.resolveConflict(
                    remoteItem = normalized,
                    localSnapshot = StoredItem(normalized.id, local.first, local.second),
                    detectedAt = nowMillis(),
                    expectedVaultId = vaultId
                )
                result.conflictsDetected++
                result.conflictedItems += normalized.id
            }
        }
    }

    private suspend fun push(
        expectedUid: String,
        vaultId: String,
        alreadyConflicted: Set<String>
    ): SyncResult {
        val result = SyncResult()
        val snapshots = try {
            localStore.getDirtySnapshots()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return result.record(SyncException.Operational())
        }

        snapshots.filterNot { it.itemId in alreadyConflicted }.forEach { snapshot ->
            try {
                pushSnapshot(expectedUid, vaultId, snapshot, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncException) {
                result.record(e, snapshot.itemId)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                result.record(SyncException.Operational(), snapshot.itemId)
            }
        }
        result.success = result.errors.isEmpty()
        return result
    }

    private suspend fun pushSnapshot(
        expectedUid: String,
        vaultId: String,
        snapshot: StoredItem,
        result: SyncResult
    ) {
        validateLocalSnapshot(snapshot)
        val remote = getRemoteItem(expectedUid, vaultId, snapshot.itemId)
        when {
            remote == null -> uploadSnapshot(expectedUid, vaultId, snapshot, null)
            remote.second.revision < snapshot.metadata.lastSyncedRevision -> throw SyncException.Rollback()
            remote.second.revision > snapshot.metadata.lastSyncedRevision -> {
                val remoteDto = validateAndNormalizeRemote(remote.toDto(snapshot.itemId))
                conflictResolver.resolveConflict(
                    remoteItem = remoteDto,
                    localSnapshot = snapshot,
                    detectedAt = nowMillis(),
                    expectedVaultId = vaultId
                )
                result.conflictsDetected++
                result.conflictedItems += snapshot.itemId
                return
            }
            snapshot.metadata.revision <= remote.second.revision -> throw SyncException.Protocol()
            else -> uploadSnapshot(expectedUid, vaultId, snapshot, remote.toRemoteData(snapshot.itemId))
        }

        // Solo este CAS puede limpiar dirty. Si hubo una edición durante la red, false
        // conserva el registro pendiente para otro ciclo.
        if (localStore.markPushSucceeded(snapshot.itemId, snapshot.metadata.revision)) {
            result.itemsPushed++
        }
    }

    private suspend fun getRemoteItem(
        expectedUid: String,
        vaultId: String,
        itemId: String
    ): Pair<Ciphertext, RemoteItemMetadata>? = try {
        remoteSource.getItem(expectedUid, vaultId, itemId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: MalformedRemoteDataException) {
        throw SyncException.Protocol()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        throw SyncException.Network()
    }

    private suspend fun uploadSnapshot(
        expectedUid: String,
        vaultId: String,
        snapshot: StoredItem,
        expected: RemoteItemData?,
    ) {
        try {
            val replacement = snapshot.toRemoteItemData()
            if (!remoteSource.replaceItemIfUnchanged(expectedUid, vaultId, expected, replacement)) {
                throw SyncException.Protocol()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: MalformedRemoteDataException) {
            throw SyncException.Protocol()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw SyncException.Network()
        }
    }

    private fun StoredItem.toRemoteItemData() = RemoteItemData(
        id = itemId,
        ciphertext = ciphertext.bytes.copyOf(),
        cryptoVersion = metadata.cryptoVersion,
        schemaVersion = metadata.schemaVersion,
        revision = metadata.revision,
        tombstone = metadata.tombstone,
        createdAt = metadata.createdAt,
        updatedAt = metadata.updatedAt,
    )

    private fun validateLocalSnapshot(snapshot: StoredItem) {
        val metadata = snapshot.metadata
        val size = snapshot.ciphertext.bytes.size
        val invalid = !metadata.dirty ||
            metadata.revision < 1 ||
            metadata.lastSyncedRevision < 0 ||
            metadata.lastSyncedRevision > metadata.revision ||
            size > MAX_CIPHERTEXT_BYTES ||
            metadata.tombstone != (size == 0)
        if (invalid) throw SyncException.Protocol()
    }

    private fun validateAndNormalizeRemote(remote: RemoteItemDto): RemoteItemDto {
        val invalid = remote.id.isBlank() ||
            remote.revision < 1 ||
            remote.cryptoVersion < 1 ||
            remote.schemaVersion < 1 ||
            remote.ciphertext.size > MAX_CIPHERTEXT_BYTES ||
            remote.tombstone != remote.ciphertext.isEmpty()
        if (invalid) throw SyncException.Protocol()
        return remote
    }

    private suspend fun EncryptedItemStore.putRemote(remote: RemoteItemDto) {
        put(
            itemId = remote.id,
            ciphertext = CiphertextMapper.fromPersistedBytes(remote.ciphertext),
            metadata = ItemLocalMetadata(
                cryptoVersion = remote.cryptoVersion,
                schemaVersion = remote.schemaVersion,
                revision = remote.revision,
                tombstone = remote.tombstone,
                createdAt = remote.createdAt,
                updatedAt = remote.updatedAt,
                dirty = false,
                lastSyncedRevision = remote.revision,
                conflictOf = null
            )
        )
    }

    private fun RemoteItemData.toDto() = RemoteItemDto(
        id = id,
        ciphertext = ciphertext,
        cryptoVersion = cryptoVersion,
        schemaVersion = schemaVersion,
        revision = revision,
        tombstone = tombstone,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Pair<Ciphertext, RemoteItemMetadata>.toDto(itemId: String) = RemoteItemDto(
        id = itemId,
        ciphertext = first.bytes,
        cryptoVersion = second.cryptoVersion,
        schemaVersion = second.schemaVersion,
        revision = second.revision,
        tombstone = second.tombstone,
        createdAt = second.createdAt,
        updatedAt = second.updatedAt
    )

    private fun Pair<Ciphertext, RemoteItemMetadata>.toRemoteData(itemId: String) = RemoteItemData(
        id = itemId,
        ciphertext = first.bytes.copyOf(),
        cryptoVersion = second.cryptoVersion,
        schemaVersion = second.schemaVersion,
        revision = second.revision,
        tombstone = second.tombstone,
        createdAt = second.createdAt,
        updatedAt = second.updatedAt,
    )

    private companion object {
        const val MAX_CIPHERTEXT_BYTES = 262_144
    }

    private data class AuthorizedVault(val uid: String, val meta: VaultMetaEntity) {
        val vaultId: String get() = meta.vaultId
    }
}

private fun VaultMetaEntity.toRemoteMetadata() = RemoteVaultMetadata(
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

private fun RemoteVaultMetadata.toLocalEntity(vaultId: String, ownerUid: String) = VaultMetaEntity(
    vaultId,
    ownerUid,
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

/** Resultado redactado de un ciclo o fase de sincronización. */
data class SyncResult(
    var success: Boolean = false,
    var itemsPushed: Int = 0,
    var itemsPulled: Int = 0,
    var itemsUpdated: Int = 0,
    var conflictsDetected: Int = 0,
    var lastPullAt: Long = 0L,
    val errors: MutableList<SyncError> = mutableListOf(),
    val failedItems: MutableList<String> = mutableListOf(),
    var error: Exception? = null,
    internal val conflictedItems: MutableSet<String> = mutableSetOf()
) {
    fun merge(other: SyncResult) {
        itemsPushed += other.itemsPushed
        itemsPulled += other.itemsPulled
        itemsUpdated += other.itemsUpdated
        conflictsDetected += other.conflictsDetected
        lastPullAt = maxOf(lastPullAt, other.lastPullAt)
        errors.addAll(other.errors)
        failedItems.addAll(other.failedItems)
        conflictedItems.addAll(other.conflictedItems)
        if (error == null) error = other.error
    }

    internal fun record(exception: SyncException, itemId: String? = null): SyncResult {
        val classified = SyncError.classify(exception)
        errors += classified
        if (classified.isPermanent && itemId != null) failedItems += itemId
        if (error == null) error = exception
        success = false
        return this
    }
}

/** Error operativo sin identificadores ni datos del registro. */
data class SyncError(
    val message: String,
    val isPermanent: Boolean,
    val code: String
) {
    companion object {
        fun classify(error: Exception): SyncError = when (error) {
            is SyncException.Network -> SyncError("Sincronización temporalmente no disponible", false, "NETWORK")
            is SyncException.Protocol -> SyncError("Datos de sincronización no válidos", true, "PROTOCOL")
            is SyncException.Rollback -> SyncError("Se rechazó una revisión remota anterior", true, "ROLLBACK")
            is SyncException.QuotaExceeded -> SyncError("Límite remoto excedido", true, "QUOTA_EXCEEDED")
            is SyncException.Unauthorized -> SyncError("Sincronización no autorizada", true, "UNAUTHORIZED")
            else -> SyncError("No se pudo completar la sincronización", true, "UNKNOWN")
        }
    }
}

/** Excepciones tipificadas y deliberadamente genéricas: no incluyen itemId ni ciphertext. */
sealed class SyncException(message: String) : Exception(message) {
    class Network : SyncException("Synchronization temporarily unavailable")
    class Protocol : SyncException("Invalid synchronization data")
    class Rollback : SyncException("Remote revision rollback rejected")
    class QuotaExceeded : SyncException("Remote quota exceeded")
    class Unauthorized : SyncException("Synchronization unauthorized")
    class Operational : SyncException("Synchronization failed")
}
