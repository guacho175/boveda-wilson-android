package cl.bovedawilson.data.sync.repo

import android.database.SQLException
import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.core.crypto.vault.VaultRecord
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.sync.mapper.VaultRecordMapper
import cl.bovedawilson.data.sync.session.UnlockLease
import cl.bovedawilson.data.sync.session.VaultSession
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Creación transaccional en dos fases: preparar en memoria, verificar y recién persistir. */
class VaultCreationRepository(
    private val metaStore: VaultMetaStore,
    private val session: VaultSession,
    private val dispatchers: AppDispatchers
) {
    private class PendingVaultCreation(
        val record: VaultRecord,
        val vault: UnlockedVault,
        val lease: UnlockLease,
    ) {
        override fun toString(): String = "PendingVaultCreation([REDACTED])"
    }

    @Volatile
    private var pendingCreation: PendingVaultCreation? = null

    suspend fun begin(password: CharArray): AppResult<List<String>, AppError> = withContext(dispatchers.default) {
        try {
            if (metaStore.getMeta() != null || pendingCreation != null) {
                return@withContext AppResult.Failure(AppError.OperationFailed)
            }
            val lease = session.beginUnlock()
                ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            val vaultId = UUID.randomUUID().toString()
            VaultCrypto.createVault(vaultId, password).fold(
                onSuccess = { created ->
                    coroutineContext.ensureActive()
                    val words = created.recoveryPhrase.toWordList()
                    pendingCreation = PendingVaultCreation(created.record, created.vault, lease)
                    AppResult.Success(words)
                },
                onFailure = { AppResult.Failure(it.toAppError()) }
            )
        } finally {
            Wipe.chars(password)
        }
    }

    /** La excepción de Room se reduce a una categoría genérica y nunca se encadena. */
    @Suppress("SwallowedException")
    suspend fun commit(): AppResult<Unit, AppError> = withContext(dispatchers.default) {
        val pending = pendingCreation ?: return@withContext AppResult.Failure(AppError.OperationFailed)
        try {
            if (metaStore.getMeta() != null) return@withContext AppResult.Failure(AppError.OperationFailed)
            if (!session.isUnlockLeaseValid(pending.lease)) {
                pendingCreation = null
                return@withContext AppResult.Failure(AppError.OperationFailed)
            }
            val now = System.currentTimeMillis()
            metaStore.saveMeta(
                VaultRecordMapper.toEntity(
                    record = pending.record,
                    ownerUid = NO_OWNER_UID,
                    createdAt = now,
                    updatedAt = now,
                    metaRevision = INITIAL_META_REVISION
                )
            )
            session.tryUnlock(pending.lease, pending.vault, pending.record.vaultId, now)
            pendingCreation = null
            // La persistencia ya es el commit irreversible local. Si el proceso pasó a
            // segundo plano en la ventana final, la bóveda queda creada pero cerrada y la
            // UI exige un desbloqueo nuevo; no se ofrece crearla otra vez.
            AppResult.Success(Unit)
        } catch (e: SQLException) {
            AppResult.Failure(AppError.OperationFailed)
        }
    }

    fun cancel() {
        pendingCreation = null
    }

    private companion object {
        const val INITIAL_META_REVISION = 1
        const val NO_OWNER_UID = ""
    }
}
