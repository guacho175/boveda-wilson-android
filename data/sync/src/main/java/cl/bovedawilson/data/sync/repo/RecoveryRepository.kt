package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import kotlinx.coroutines.withContext

/**
 * Fachada del camino de recuperación. Delega en [VaultRepository], que es quien tiene el
 * envoltorio y la sesión; aquí no vive material criptográfico.
 *
 * [validateRecoveryPhrase] queda pendiente de la Fase 8: validar el checksum BIP-39 sin
 * intentar el desenvolvido exige exponer el códec desde `:core:crypto`, y hoy la
 * validación real ocurre dentro de [unlockWithRecoveryPhrase] (una palabra incorrecta
 * rompe el checksum o la autenticación).
 */
class RecoveryRepository(
    private val vaultRepository: VaultRepository,
    private val dispatchers: AppDispatchers
) {
    suspend fun unlockWithRecoveryPhrase(phrase: List<String>): AppResult<Unit, AppError> =
        withContext(dispatchers.io) {
            vaultRepository.unlockVaultWithRecovery(phrase)
        }

    suspend fun regenerateRecoveryPhrase(password: CharArray): AppResult<List<String>, AppError> =
        withContext(dispatchers.io) {
            vaultRepository.regenerateRecoveryPhrase(password)
        }

    @Suppress("UnusedParameter")
    suspend fun validateRecoveryPhrase(phrase: List<String>): AppResult<Boolean, AppError> =
        withContext(dispatchers.io) {
            AppResult.Failure(AppError.OperationFailed)
        }
}
