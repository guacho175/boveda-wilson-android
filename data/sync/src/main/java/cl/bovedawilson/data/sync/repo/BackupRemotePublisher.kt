package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.remote.auth.FirebaseAuthSource
import cl.bovedawilson.data.remote.firestore.FirestoreVaultSource
import cl.bovedawilson.data.remote.firestore.RemoteItemData
import cl.bovedawilson.data.remote.firestore.RemoteVaultMetadata
import cl.bovedawilson.data.remote.firestore.RemoteVaultValidator
import cl.bovedawilson.data.sync.backup.BackupItem
import cl.bovedawilson.data.sync.backup.BackupSnapshot
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Publica una restauración ya autenticada sin sobrescribir ningún estado remoto divergente. */
@Suppress("LongParameterList")
class BackupRemotePublisher(
    private val auth: FirebaseAuthSource,
    private val remote: FirestoreVaultSource,
    private val metaStore: VaultMetaStore,
    private val itemDao: EncryptedItemDao,
    private val dispatchers: AppDispatchers,
    private val coordinator: SyncCoordinator,
    private val authorizer: BackupPublicationAuthorizer,
) {
    suspend fun publish(snapshot: BackupSnapshot): AppResult<Unit, AppError> =
        withContext(dispatchers.io) {
            coordinator.exclusive { publishExclusive(snapshot) }
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun publishExclusive(snapshot: BackupSnapshot): AppResult<Unit, AppError> {
        return try {
            if (!authorizer.isAuthorized(snapshot)) return AppResult.Failure(AppError.OperationFailed)
            val uid = auth.currentUserId ?: return AppResult.Failure(AppError.OperationFailed)
            val local = metaStore.getMeta() ?: return AppResult.Failure(AppError.OperationFailed)
            val localItems = itemDao.getAllItems()
            if (!local.matchesRestoredState(snapshot, uid, localItems)) return failure()
            val baselineMeta = snapshot.toRemoteMetadata()
            val expectedItems = snapshot.items.associate { it.itemId to it.toRemoteItem() }
            val candidateMeta = local.toFinalRemoteMetadata(baselineMeta)
            val firstMeta = remote.getVaultMeta(uid, snapshot.vaultId)
            val firstItems = remote.listItems(uid, snapshot.vaultId).associateBy(RemoteItemData::id)
            if (auth.currentUserId != uid) return AppResult.Failure(AppError.OperationFailed)

            when {
                firstMeta == null && firstItems.isEmpty() -> {
                    remote.createVaultMeta(uid, local.vaultId, baselineMeta)
                    uploadItemsAndFinalize(
                        snapshot,
                        uid,
                        local,
                        localItems,
                        baselineMeta,
                        candidateMeta,
                        emptyMap(),
                    )
                }
                firstMeta != null &&
                    RemoteVaultValidator.hasSameContent(firstMeta, baselineMeta) &&
                    firstItems.isSafeSubsetOf(expectedItems) -> {
                    uploadItemsAndFinalize(
                        snapshot,
                        uid,
                        local,
                        localItems,
                        baselineMeta,
                        candidateMeta,
                        firstItems,
                    )
                }
                firstMeta != null &&
                    RemoteVaultValidator.hasSameContent(firstMeta, candidateMeta) &&
                    firstItems == expectedItems -> {
                    finishLocal(
                        snapshot,
                        uid,
                        local,
                        localItems,
                        candidateMeta,
                        expectedItems,
                    )
                }
                else -> AppResult.Failure(AppError.RemoteConflict)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Failure(AppError.OperationFailed)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun uploadItemsAndFinalize(
        snapshot: BackupSnapshot,
        uid: String,
        local: VaultMetaEntity,
        items: List<EncryptedItemEntity>,
        baseline: RemoteVaultMetadata,
        candidate: RemoteVaultMetadata,
        existing: Map<String, RemoteItemData>,
    ): AppResult<Unit, AppError> {
        val expected = items.associate { it.itemId to it.toRemoteItem() }
        if (!existing.isSafeSubsetOf(expected)) return AppResult.Failure(AppError.RemoteConflict)
        for ((id, item) in expected) {
            if (existing[id] == null && !remote.createItemIfAbsentOrIdentical(uid, local.vaultId, item)) {
                return AppResult.Failure(AppError.RemoteConflict)
            }
        }
        if (!remoteMatches(uid, local.vaultId, baseline, expected) ||
            auth.currentUserId != uid ||
            !authorizer.isAuthorized(snapshot)
        ) {
            return AppResult.Failure(AppError.RemoteConflict)
        }
        if (!remote.replaceVaultMetaIfUnchanged(uid, local.vaultId, baseline, candidate)) {
            if (!remoteMatches(uid, local.vaultId, candidate, expected)) {
                return AppResult.Failure(AppError.RemoteConflict)
            }
        }
        return finishLocal(snapshot, uid, local, items, candidate, expected)
    }

    private suspend fun finishLocal(
        snapshot: BackupSnapshot,
        uid: String,
        local: VaultMetaEntity,
        items: List<EncryptedItemEntity>,
        candidate: RemoteVaultMetadata,
        expectedItems: Map<String, RemoteItemData>,
    ): AppResult<Unit, AppError> {
        return when {
            !remoteMatches(uid, local.vaultId, candidate, expectedItems) || auth.currentUserId != uid ->
                AppResult.Failure(AppError.RemoteConflict)
            !authorizer.consume(snapshot) -> AppResult.Failure(AppError.OperationFailed)
            else -> {
                metaStore.saveMeta(
                    local.copy(ownerUid = uid, updatedAt = candidate.updatedAt, metaRevision = candidate.metaRevision),
                )
                items.forEach { item -> itemDao.markPushSucceeded(item.itemId, item.revision) }
                AppResult.Success(Unit)
            }
        }
    }

    private suspend fun remoteMatches(
        uid: String,
        vaultId: String,
        expectedMeta: RemoteVaultMetadata,
        expectedItems: Map<String, RemoteItemData>,
    ): Boolean {
        val actualMeta = remote.getVaultMeta(uid, vaultId) ?: return false
        val actualItems = remote.listItems(uid, vaultId).associateBy(RemoteItemData::id)
        return RemoteVaultValidator.hasSameContent(actualMeta, expectedMeta) && actualItems == expectedItems
    }
}

@Suppress("CyclomaticComplexMethod")
private fun VaultMetaEntity.matchesRestoredSnapshot(snapshot: BackupSnapshot, uid: String): Boolean =
    vaultId == snapshot.vaultId &&
        (ownerUid.isEmpty() || ownerUid == uid) &&
        schemaVersion == snapshot.schemaVersion &&
        cryptoVersion == snapshot.cryptoVersion &&
        kdfName == snapshot.kdfName &&
        kdfMemoryKib == snapshot.kdfMemoryKib &&
        kdfIterations == snapshot.kdfIterations &&
        kdfParallelism == snapshot.kdfParallelism &&
        kdfOutputLen == snapshot.kdfOutputLen &&
        passwordWrapEpoch > snapshot.passwordWrapEpoch &&
        recoveryWrapEpoch > snapshot.recoveryWrapEpoch &&
        createdAt == snapshot.createdAt &&
        updatedAt == snapshot.updatedAt &&
        metaRevision == snapshot.metaRevision

private fun List<EncryptedItemEntity>.match(items: List<BackupItem>): Boolean =
    associate { it.itemId to it.toRemoteItem() } == items.associate { it.itemId to it.toRemoteItem() }

private fun VaultMetaEntity.matchesRestoredState(
    snapshot: BackupSnapshot,
    uid: String,
    items: List<EncryptedItemEntity>,
): Boolean = matchesRestoredSnapshot(snapshot, uid) && items.match(snapshot.items)

private fun failure(): AppResult.Failure<AppError> = AppResult.Failure(AppError.OperationFailed)

private fun Map<String, RemoteItemData>.isSafeSubsetOf(expected: Map<String, RemoteItemData>): Boolean =
    all { (id, item) -> expected[id] == item }

private fun BackupItem.toRemoteItem() = RemoteItemData(
    id = itemId,
    ciphertext = ciphertext.copyOf(),
    cryptoVersion = cryptoVersion,
    schemaVersion = schemaVersion,
    revision = revision,
    tombstone = tombstone,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun EncryptedItemEntity.toRemoteItem() = RemoteItemData(
    id = itemId,
    ciphertext = ciphertext.copyOf(),
    cryptoVersion = cryptoVersion,
    schemaVersion = schemaVersion,
    revision = revision,
    tombstone = tombstone,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun BackupSnapshot.toRemoteMetadata() = RemoteVaultMetadata(
    schemaVersion = schemaVersion,
    cryptoVersion = cryptoVersion,
    kdfName = kdfName,
    kdfMemoryKib = kdfMemoryKib,
    kdfIterations = kdfIterations,
    kdfParallelism = kdfParallelism,
    kdfOutputLen = kdfOutputLen,
    passwordSalt = passwordSalt.copyOf(),
    passwordWrappedVdek = passwordWrappedVdek.copyOf(),
    recoverySalt = recoverySalt.copyOf(),
    recoveryWrappedVdek = recoveryWrappedVdek.copyOf(),
    passwordWrapEpoch = passwordWrapEpoch,
    recoveryWrapEpoch = recoveryWrapEpoch,
    createdAt = createdAt,
    updatedAt = updatedAt,
    metaRevision = metaRevision,
)

private fun VaultMetaEntity.toRemoteMetadata() = RemoteVaultMetadata(
    schemaVersion = schemaVersion,
    cryptoVersion = cryptoVersion,
    kdfName = kdfName,
    kdfMemoryKib = kdfMemoryKib,
    kdfIterations = kdfIterations,
    kdfParallelism = kdfParallelism,
    kdfOutputLen = kdfOutputLen,
    passwordSalt = passwordSalt.copyOf(),
    passwordWrappedVdek = passwordWrappedVdek.copyOf(),
    recoverySalt = recoverySalt.copyOf(),
    recoveryWrappedVdek = recoveryWrappedVdek.copyOf(),
    passwordWrapEpoch = passwordWrapEpoch,
    recoveryWrapEpoch = recoveryWrapEpoch,
    createdAt = createdAt,
    updatedAt = updatedAt,
    metaRevision = metaRevision,
)

private fun VaultMetaEntity.toFinalRemoteMetadata(baseline: RemoteVaultMetadata): RemoteVaultMetadata =
    toRemoteMetadata().copy(
        createdAt = baseline.createdAt,
        updatedAt = maxOf(updatedAt, baseline.updatedAt),
        metaRevision = baseline.metaRevision + 1,
    )
