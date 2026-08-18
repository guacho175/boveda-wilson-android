package cl.bovedawilson.app.ui.unlock

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.data.sync.repo.BiometricUnlockRepository
import cl.bovedawilson.data.sync.repo.SettingsRepository
import cl.bovedawilson.data.sync.repo.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Estado de la pantalla de desbloqueo. **No contiene secretos**: la contraseña y las
 * palabras de recuperación viajan como argumento de la acción y se borran en el
 * repositorio, nunca se guardan aquí (`docs/architecture.md` §4).
 */
data class UnlockUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val unlockSuccess: Boolean = false,
    val biometricAvailable: Boolean = false
)

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val biometricUnlockRepository: BiometricUnlockRepository,
    private val settings: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    /** Ambas condiciones: preferencia del usuario activada y hay un conjunto biométrico
     * enrolado para esta bóveda. La disponibilidad de hardware se comprueba aparte, justo
     * antes de mostrar el prompt, porque puede cambiar entre lecturas. */
    val biometricEnabledPreference: StateFlow<Boolean> = settings.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun unlockWithPassword(password: CharArray) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            handle(vaultRepository.unlockVault(password))
        }
    }

    fun unlockWithRecovery(phrase: List<String>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            handle(vaultRepository.unlockVaultWithRecovery(phrase))
        }
    }

    /** true si hay hardware disponible y un conjunto biométrico enrolado para esta bóveda:
     * lo que decide si se muestra el botón de desbloqueo biométrico. */
    suspend fun canOfferBiometricUnlock(): Boolean =
        biometricUnlockRepository.isBiometricHardwareAvailable() && biometricUnlockRepository.hasBiometricEnrollment()

    suspend fun prepareBiometricUnlockCipher(): BiometricPrompt.CryptoObject? =
        biometricUnlockRepository.prepareUnlockCipher()

    fun unlockWithBiometric(cipher: Cipher) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            handle(biometricUnlockRepository.unlockWithBiometric(cipher))
        }
    }

    private fun handle(result: AppResult<Unit, AppError>) {
        result.fold(
            onSuccess = {
                _uiState.value = _uiState.value.copy(isLoading = false, unlockSuccess = true)
            },
            onFailure = { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = error.toUserMessage()
                )
            }
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Traduce el error tipado a un texto para la persona usuaria. No distingue contraseña
 * incorrecta de ciphertext alterado más allá de lo que hace falta para actuar, y nunca
 * incluye material ni detalle interno (`SECURITY.md` §4).
 */
internal fun AppError.toUserMessage(): String = when (this) {
    AppError.InvalidCredentials -> "No se pudo desbloquear la bóveda. Revisa lo que ingresaste."
    AppError.IntegrityFailure -> "No se pudo descifrar el contenido."
    AppError.UnsupportedVersion -> "Esta bóveda usa un formato que esta versión no reconoce."
    AppError.WeakParameters -> "Los parámetros de la bóveda no son válidos."
    AppError.MalformedInput -> "Los datos ingresados no tienen el formato esperado."
    AppError.RemoteConflict -> "La bóveda remota cambió. No se sobrescribió ningún dato."
    AppError.OperationFailed -> "No se pudo completar la operación."
}
