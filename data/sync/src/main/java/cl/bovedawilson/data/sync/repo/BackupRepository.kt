package cl.bovedawilson.data.sync.repo

import androidx.room.withTransaction
import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.vault.RestoredVault
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.dao.VaultMetaDao
import cl.bovedawilson.data.local.db.VaultDatabase
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.sync.backup.BackupFormat
import cl.bovedawilson.data.sync.backup.BackupFormatException
import cl.bovedawilson.data.sync.backup.BackupSnapshot
import cl.bovedawilson.data.sync.backup.BackupUnsupportedVersionException
import cl.bovedawilson.data.sync.engine.SyncCoordinator
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Resultado de restaurar un respaldo; la frase solo se entrega cuando debe mostrarse una vez. */
class BackupRestoreResult(val recoveryPhrase: List<String>?) {
    override fun toString(): String = "BackupRestoreResult(recoveryPhrase=${recoveryPhrase != null})"
}

/**
 * Exporta y restaura exclusivamente las filas opacas de Room. No acepta ni devuelve [VaultItem]:
 * el contrato de persistencia es `VaultMetaEntity` + ciphertext y metadatos públicos.
 */
@Suppress("LongParameterList")
class BackupRepository(
    private val database: VaultDatabase,
    private val metaDao: VaultMetaDao,
    private val metaStore: VaultMetaStore,
    private val itemDao: EncryptedItemDao,
    private val dispatchers: AppDispatchers,
    private val session: VaultSession,
    private val remotePublisher: BackupRemotePublisher? = null,
    private val publicationAuthorizer: BackupPublicationAuthorizer? = null,
    private val coordinator: SyncCoordinator = SyncCoordinator(),
) {
    suspend fun exportVault(
        outputFactory: () -> OutputStream?,
        masterPassword: CharArray,
    ): AppResult<Unit, AppError> = withContext(dispatchers.io) {
        try {
            val unlockedState = session.state.value as? SessionState.Unlocked
            val unlockedVault = session.getVault()
            val generation = session.securityGeneration()
            if (unlockedState == null || unlockedVault == null) {
                return@withContext AppResult.Failure(AppError.OperationFailed)
            }
            val snapshot = createAuthenticatedSnapshot(masterPassword, unlockedVault)
            val bytes = BackupFormat.encode(snapshot)
            val sameSession = session.state.value == unlockedState &&
                session.getVault() === unlockedVault &&
                session.securityGeneration() == generation
            if (!sameSession) return@withContext AppResult.Failure(AppError.OperationFailed)
            // La serialización, los límites y la reautenticación terminan antes de tocar el destino.
            val output = outputFactory() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            output.use {
                it.write(bytes)
                it.flush()
            }
            AppResult.Success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            AppResult.Failure(error.toBackupError())
        } finally {
            Wipe.chars(masterPassword)
        }
    }

    private suspend fun createAuthenticatedSnapshot(
        masterPassword: CharArray,
        unlockedVault: cl.bovedawilson.core.crypto.session.UnlockedVault,
    ): BackupSnapshot {
        val unsignedSnapshot = database.withTransaction {
            val meta = metaDao.getMeta() ?: throw BackupFormatException
            val sizeStats = itemDao.getBackupSizeStats()
            requireBackupFormat(
                sizeStats.maxCiphertextBytes <= BackupFormat.MAX_CIPHERTEXT_BYTES &&
                    BackupFormat.isExportSizeAllowed(sizeStats.itemCount, sizeStats.ciphertextBytes),
            )
            val currentItems = itemDao.getAllItems()
            val actualBytes = currentItems.sumOf { it.ciphertext.size.toLong() }
            requireBackupFormat(BackupFormat.isExportSizeAllowed(currentItems.size.toLong(), actualBytes))
            BackupFormat.fromMeta(meta, currentItems)
        }
        val authenticatedVault = VaultCrypto.unlockWithPassword(unsignedSnapshot.toRecord(), masterPassword).orThrow()
        val snapshot = BackupFormat.authenticate(unsignedSnapshot, authenticatedVault)
        if (!BackupFormat.isAuthentic(snapshot, unlockedVault)) throw CryptoError.IntegrityFailure
        return snapshot
    }

    suspend fun restoreWithPassword(
        input: InputStream,
        masterPassword: CharArray,
        currentVaultPassword: CharArray = charArrayOf(),
        replacementConfirmation: CharArray = charArrayOf(),
    ): AppResult<BackupRestoreResult, AppError> = withContext(dispatchers.io) {
        coordinator.exclusive {
            try {
                publicationAuthorizer?.clear()
                val snapshot = BackupFormat.decode(readBounded(input))
                val current = metaStore.getMeta()
                val permit = authorizeReplacement(
                    snapshot,
                    current,
                    currentVaultPassword,
                    replacementConfirmation,
                )
                val local = current?.takeIf { it.vaultId == snapshot.vaultId }
                val restored = VaultCrypto.restoreWithPassword(
                    snapshot.toRecord(),
                    masterPassword,
                    passwordEpochFloor = local?.passwordWrapEpoch ?: 0,
                    recoveryEpochFloor = local?.recoveryWrapEpoch ?: 0,
                )
                restored.fold(
                    onSuccess = {
                        if (BackupFormat.isAuthentic(snapshot, it.vault)) {
                            commit(snapshot, it, permit)
                        } else {
                            AppResult.Failure(AppError.IntegrityFailure)
                        }
                    },
                    onFailure = { AppResult.Failure(it.toBackupAppError()) },
                )
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppResult.Failure(error.toBackupError())
            } finally {
                Wipe.chars(masterPassword)
                Wipe.chars(currentVaultPassword)
                Wipe.chars(replacementConfirmation)
            }
        }
    }

    suspend fun restoreWithRecovery(
        input: InputStream,
        recoveryPhrase: List<String>,
        newMasterPassword: CharArray,
        currentVaultPassword: CharArray = charArrayOf(),
        replacementConfirmation: CharArray = charArrayOf(),
    ): AppResult<BackupRestoreResult, AppError> = withContext(dispatchers.io) {
        coordinator.exclusive {
            try {
                publicationAuthorizer?.clear()
                val snapshot = BackupFormat.decode(readBounded(input))
                val current = metaStore.getMeta()
                val permit = authorizeReplacement(
                    snapshot,
                    current,
                    currentVaultPassword,
                    replacementConfirmation,
                )
                val local = current?.takeIf { it.vaultId == snapshot.vaultId }
                val restored = VaultCrypto.restoreWithRecovery(
                    snapshot.toRecord(),
                    recoveryPhrase,
                    newMasterPassword,
                    passwordEpochFloor = local?.passwordWrapEpoch ?: 0,
                    recoveryEpochFloor = local?.recoveryWrapEpoch ?: 0,
                )
                restored.fold(
                    onSuccess = {
                        if (BackupFormat.isAuthentic(snapshot, it.vault)) {
                            commit(snapshot, it, permit)
                        } else {
                            AppResult.Failure(AppError.IntegrityFailure)
                        }
                    },
                    onFailure = { AppResult.Failure(it.toBackupAppError()) },
                )
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppResult.Failure(error.toBackupError())
            } finally {
                Wipe.chars(newMasterPassword)
                Wipe.chars(currentVaultPassword)
                Wipe.chars(replacementConfirmation)
            }
        }
    }

    suspend fun validateBackupFile(input: InputStream): AppResult<Boolean, AppError> =
        withContext(dispatchers.io) {
            try {
                BackupFormat.decode(readBounded(input))
                AppResult.Success(true)
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppResult.Failure(error.toBackupError())
            } finally {
                input.close()
            }
        }

    suspend fun publishRestoredBackup(input: InputStream): AppResult<Unit, AppError> =
        withContext(dispatchers.io) {
            try {
                val snapshot = BackupFormat.decode(readBounded(input))
                remotePublisher?.publish(snapshot)
                    ?: AppResult.Failure(AppError.OperationFailed)
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                AppResult.Failure(error.toBackupError())
            } finally {
                input.close()
            }
        }

    private suspend fun commit(
        snapshot: BackupSnapshot,
        restored: RestoredVault,
        replacementPermit: ReplacementPermit?,
    ): AppResult<BackupRestoreResult, AppError> {
        val current = metaStore.getMeta()
        if (current?.vaultId != snapshot.vaultId && !replacementPermit.isStillValid(current, snapshot.vaultId)) {
            return AppResult.Failure(AppError.OperationFailed)
        }
        val ownerUid = current?.takeIf { it.vaultId == snapshot.vaultId }?.ownerUid.orEmpty()
        val meta = snapshot.toMeta(restored.record, ownerUid)
        val items = snapshot.items.map { item ->
            EncryptedItemEntity(
                itemId = item.itemId,
                ciphertext = item.ciphertext.copyOf(),
                cryptoVersion = item.cryptoVersion,
                schemaVersion = item.schemaVersion,
                revision = item.revision,
                tombstone = item.tombstone,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                dirty = true,
                lastSyncedRevision = 0,
                conflictOf = null,
                pendingRemoteCiphertext = null,
                pendingRemoteRevision = null,
                pendingRemoteCryptoVersion = null,
                pendingRemoteSchemaVersion = null,
                pendingRemoteTombstone = null,
                pendingRemoteCreatedAt = null,
                pendingRemoteUpdatedAt = null,
            )
        }
        // El estado anterior deja de ser válido en cuanto empieza el reemplazo local.
        // Si Room falla después, el resultado seguro sigue siendo una sesión bloqueada.
        session.lock()
        database.withTransaction {
            metaDao.insertOrUpdate(meta)
            itemDao.deleteAll()
            itemDao.insertOrReplaceAll(items)
        }
        publicationAuthorizer?.authorize(snapshot, restored.vault)
        return AppResult.Success(BackupRestoreResult(restored.recoveryPhrase?.toWordList()))
    }

    private fun authorizeReplacement(
        snapshot: BackupSnapshot,
        current: cl.bovedawilson.data.local.entity.VaultMetaEntity?,
        currentPassword: CharArray,
        confirmation: CharArray,
    ): ReplacementPermit? {
        if (current == null || current.vaultId == snapshot.vaultId) return null
        val unlocked = session.state.value as? SessionState.Unlocked
        if (unlocked?.vaultId != current.vaultId || !confirmation.matchesReplacementConfirmation()) {
            throw ReplacementNotAuthorizedException
        }
        val currentRecord = BackupFormat.fromMeta(current, emptyList()).toRecord()
        when (VaultCrypto.unlockWithPassword(currentRecord, currentPassword)) {
            is AppResult.Failure -> throw ReplacementNotAuthorizedException
            is AppResult.Success -> Unit
        }
        return ReplacementPermit(
            currentVaultId = current.vaultId,
            replacementVaultId = snapshot.vaultId,
            currentMetaRevision = current.metaRevision,
            generation = session.securityGeneration(),
        )
    }

    private fun ReplacementPermit?.isStillValid(
        current: cl.bovedawilson.data.local.entity.VaultMetaEntity?,
        replacementVaultId: String,
    ): Boolean = this != null &&
        current != null &&
        current.vaultId == currentVaultId &&
        current.metaRevision == currentMetaRevision &&
        replacementVaultId == this.replacementVaultId &&
        session.securityGeneration() == generation &&
        (session.state.value as? SessionState.Unlocked)?.vaultId == currentVaultId

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > BackupFormat.MAX_FILE_BYTES) throw BackupFormatException
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private class ReplacementPermit(
        val currentVaultId: String,
        val replacementVaultId: String,
        val currentMetaRevision: Int,
        val generation: Long,
    )

    companion object {
        const val REPLACEMENT_CONFIRMATION = "REEMPLAZAR"
    }
}

private fun CharArray.matchesReplacementConfirmation(): Boolean =
    size == BackupRepository.REPLACEMENT_CONFIRMATION.length &&
        indices.all { index -> this[index] == BackupRepository.REPLACEMENT_CONFIRMATION[index] }

private data object ReplacementNotAuthorizedException : Exception()

private fun requireBackupFormat(condition: Boolean) {
    if (!condition) throw BackupFormatException
}

private fun <T> AppResult<T, CryptoError>.orThrow(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw error
}

private fun CryptoError.toBackupAppError(): AppError = when (this) {
    CryptoError.InvalidCredentials -> AppError.InvalidCredentials
    CryptoError.IntegrityFailure -> AppError.IntegrityFailure
    CryptoError.UnsupportedVersion -> AppError.UnsupportedVersion
    CryptoError.WeakParameters -> AppError.WeakParameters
    CryptoError.MalformedInput -> AppError.MalformedInput
    CryptoError.InternalError -> AppError.OperationFailed
}

private fun Exception.toBackupError(): AppError = when (this) {
    is BackupUnsupportedVersionException -> AppError.UnsupportedVersion
    is BackupFormatException -> AppError.MalformedInput
    is CryptoError -> toBackupAppError()
    else -> AppError.OperationFailed
}
