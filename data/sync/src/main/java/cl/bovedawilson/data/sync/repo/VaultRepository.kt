package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.core.crypto.vault.VaultRecord
import cl.bovedawilson.core.model.VaultMetaView
import cl.bovedawilson.data.local.dao.EncryptedItemDao
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.sync.engine.ConflictResolver
import cl.bovedawilson.data.sync.mapper.VaultRecordMapper
import cl.bovedawilson.data.sync.session.UnlockLease
import cl.bovedawilson.data.sync.session.VaultSession
import cl.bovedawilson.data.sync.worker.NoOpSyncScheduler
import cl.bovedawilson.data.sync.worker.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Ciclo de vida de la bóveda: creación, desbloqueo por contraseña o por frase, cambio de
 * contraseña maestra y regeneración de la recuperación. El desbloqueo biométrico vive
 * aparte, en [BiometricUnlockRepository]: mismo `vault_meta`, pero un ciclo de vida propio
 * (activar/desactivar/invalidar) que habría hecho crecer esta clase por encima del límite
 * de funciones de Detekt.
 *
 * Fronteras que respeta:
 * - La contraseña maestra y la entropía **nunca** se persisten ni se registran; llegan como
 *   `CharArray` y se borran en un `finally` (`SECURITY.md` §1).
 * - Lo único que toca `vault_meta` es el envoltorio público: salts, parámetros de KDF y
 *   keysets ya cifrados. La VDEK solo existe dentro de `UnlockedVault`, en memoria.
 * - Los errores salen como [AppError], una categoría cerrada sin material sensible.
 *
 * El trabajo de KDF (Argon2id, ~1,5 s en el dispositivo de referencia) va al dispatcher de
 * cómputo; el acceso a Room, al de E/S (`docs/architecture.md` §9).
 */
class VaultRepository(
    private val metaStore: VaultMetaStore,
    private val itemDao: EncryptedItemDao,
    private val session: VaultSession,
    private val dispatchers: AppDispatchers,
    private val conflictResolver: ConflictResolver,
    private val syncScheduler: SyncScheduler = NoOpSyncScheduler
) {

    /** True si ya existe una bóveda en este dispositivo. Decide la pantalla de arranque. */
    suspend fun hasVault(): Boolean = withContext(dispatchers.io) { metaStore.getMeta() != null }

    fun observeHasVault(): Flow<Boolean> = metaStore.observeMeta().map { it != null }

    suspend fun unlockVault(password: CharArray): AppResult<Unit, AppError> =
        withContext(dispatchers.default) {
            try {
                val lease = session.beginUnlock()
                    ?: return@withContext AppResult.Failure(AppError.OperationFailed)
                val record = loadRecord() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
                VaultCrypto.unlockWithPassword(record, password).fold(
                    onSuccess = { vault ->
                        unlockAndResolveConflicts(vault, record.vaultId, lease)
                    },
                    onFailure = { AppResult.Failure(it.toAppError()) }
                )
            } finally {
                Wipe.chars(password)
            }
        }

    suspend fun unlockVaultWithRecovery(phrase: List<String>): AppResult<Unit, AppError> =
        withContext(dispatchers.default) {
            val lease = session.beginUnlock()
                ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            val record = loadRecord() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            VaultCrypto.unlockWithRecovery(record, phrase).fold(
                onSuccess = { vault ->
                    unlockAndResolveConflicts(vault, record.vaultId, lease)
                },
                onFailure = { AppResult.Failure(it.toAppError()) }
            )
        }

    fun lockVault() {
        session.lock()
    }

    /**
     * Cambia la contraseña maestra. Reenvuelve la misma VDEK, así que **todas las notas
     * siguen descifrables** y la frase de recuperación vigente **no** se invalida
     * (`CRYPTOGRAPHY.md` §15). La contraseña actual actúa como reautenticación.
     */
    suspend fun changePassword(
        oldPassword: CharArray,
        newPassword: CharArray
    ): AppResult<Unit, AppError> = withContext(dispatchers.default) {
        try {
            val entity = metaStore.getMeta() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            val record = VaultRecordMapper.toRecord(entity)
            VaultCrypto.changeMasterPassword(record, oldPassword, newPassword).fold(
                onSuccess = { updated ->
                    metaStore.saveMeta(
                        VaultRecordMapper.toEntity(
                            record = updated,
                            ownerUid = entity.ownerUid,
                            createdAt = entity.createdAt,
                            updatedAt = System.currentTimeMillis(),
                            metaRevision = entity.metaRevision + 1
                        )
                    )
                    syncScheduler.syncNowIfAuthorized()
                    AppResult.Success(Unit)
                },
                onFailure = { AppResult.Failure(it.toAppError()) }
            )
        } finally {
            Wipe.chars(oldPassword)
            Wipe.chars(newPassword)
        }
    }

    /**
     * Regenera la frase de 24 palabras. Exige la contraseña maestra como reautenticación
     * (`SECURITY.md` §5). La frase anterior deja de servir contra el
     * envoltorio vigente, pero eso **no revoca** una copia antigua ya obtenida por alguien
     * que se llevara el `recoveryWrappedVdek` previo (`CRYPTOGRAPHY.md` §15).
     */
    suspend fun regenerateRecoveryPhrase(password: CharArray): AppResult<List<String>, AppError> =
        withContext(dispatchers.default) {
            try {
                val entity = metaStore.getMeta() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
                val record = VaultRecordMapper.toRecord(entity)
                VaultCrypto.regenerateRecovery(record, password).fold(
                    onSuccess = { regenerated ->
                        metaStore.saveMeta(
                            VaultRecordMapper.toEntity(
                                record = regenerated.record,
                                ownerUid = entity.ownerUid,
                                createdAt = entity.createdAt,
                                updatedAt = System.currentTimeMillis(),
                                metaRevision = entity.metaRevision + 1
                            )
                        )
                        syncScheduler.syncNowIfAuthorized()
                        AppResult.Success(regenerated.recoveryPhrase.toWordList())
                    },
                    onFailure = { AppResult.Failure(it.toAppError()) }
                )
            } finally {
                Wipe.chars(password)
            }
        }

    suspend fun getVaultMeta(): VaultMetaView? = withContext(dispatchers.io) {
        val entity = metaStore.getMeta() ?: return@withContext null
        VaultMetaView(
            vaultId = entity.vaultId,
            createdAt = entity.createdAt,
            itemCount = itemDao.countActive()
        )
    }

    private suspend fun loadRecord(): VaultRecord? =
        metaStore.getMeta()?.let(VaultRecordMapper::toRecord)

    /** Resolver staging forma parte del desbloqueo: ante un fallo se vuelve a cerrar la
     * sesión para no dejar un resultado de error con una capacidad criptográfica viva. */
    // La causa se omite a propósito: podría incluir datos de la operación criptográfica.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun unlockAndResolveConflicts(
        vault: cl.bovedawilson.core.crypto.session.UnlockedVault,
        vaultId: String,
        lease: UnlockLease
    ): AppResult<Unit, AppError> {
        return try {
            if (!session.isUnlockLeaseValid(lease)) {
                return AppResult.Failure(AppError.OperationFailed)
            }
            conflictResolver.resolveAllPending(vault, vaultId) {
                session.isUnlockLeaseValid(lease)
            }
            if (session.tryUnlock(lease, vault, vaultId)) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.OperationFailed)
            }
        } catch (e: CancellationException) {
            session.lock()
            throw e
        } catch (e: Exception) {
            session.lock()
            AppResult.Failure(AppError.OperationFailed)
        }
    }
}

/**
 * Traducción de la frontera `:core:crypto` → `:app` (`docs/architecture.md` §2). Ambos
 * conjuntos son categorías cerradas sin material sensible, así que el mapeo no puede
 * filtrar nada. `internal` (no `private`): también la usa [BiometricUnlockRepository].
 */
internal fun CryptoError.toAppError(): AppError = when (this) {
    CryptoError.InvalidCredentials -> AppError.InvalidCredentials
    CryptoError.IntegrityFailure -> AppError.IntegrityFailure
    CryptoError.UnsupportedVersion -> AppError.UnsupportedVersion
    CryptoError.WeakParameters -> AppError.WeakParameters
    CryptoError.MalformedInput -> AppError.MalformedInput
    CryptoError.InternalError -> AppError.OperationFailed
}
