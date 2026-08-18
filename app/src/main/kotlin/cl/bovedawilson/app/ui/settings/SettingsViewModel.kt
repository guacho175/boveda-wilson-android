package cl.bovedawilson.app.ui.settings

import android.app.Activity
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.bovedawilson.app.ui.cloud.GoogleIdTokenProvider
import cl.bovedawilson.app.ui.unlock.toUserMessage
import cl.bovedawilson.data.sync.repo.BiometricUnlockRepository
import cl.bovedawilson.data.sync.repo.CloudAccessRepository
import cl.bovedawilson.data.sync.repo.SettingsRepository
import cl.bovedawilson.data.sync.repo.VaultLifecycleRepository
import cl.bovedawilson.data.sync.repo.VaultRepository
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

sealed class SettingsDestination {
    data object CloudAccess : SettingsDestination()
}

@Suppress("LongParameterList")
class SettingsUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    /** Solo poblada mientras la pantalla muestra la frase recién regenerada; se descarta
     * al confirmar y en [SettingsViewModel.onCleared]. Nunca se persiste (ADR-011). */
    val recoveryPhrase: List<String>? = null,
    val biometricHardwareAvailable: Boolean = false,
    val biometricEnrolled: Boolean = false,
    val firebaseSignedIn: Boolean = false,
    val isLeavingVault: Boolean = false
) {
    // Mantiene la ergonomía de actualización de StateFlow sin volver a `data class`,
    // cuya representación generada expondría recoveryPhrase.
    @Suppress("LongParameterList")
    fun copy(
        isLoading: Boolean = this.isLoading,
        errorMessage: String? = this.errorMessage,
        successMessage: String? = this.successMessage,
        recoveryPhrase: List<String>? = this.recoveryPhrase,
        biometricHardwareAvailable: Boolean = this.biometricHardwareAvailable,
        biometricEnrolled: Boolean = this.biometricEnrolled,
        firebaseSignedIn: Boolean = this.firebaseSignedIn,
        isLeavingVault: Boolean = this.isLeavingVault
    ): SettingsUiState = SettingsUiState(
        isLoading = isLoading,
        errorMessage = errorMessage,
        successMessage = successMessage,
        recoveryPhrase = recoveryPhrase,
        biometricHardwareAvailable = biometricHardwareAvailable,
        biometricEnrolled = biometricEnrolled,
        firebaseSignedIn = firebaseSignedIn,
        isLeavingVault = isLeavingVault
    )

    override fun toString(): String = "SettingsUiState([REDACTED])"
}

@HiltViewModel
// Los repositorios y el adaptador de credenciales representan controles independientes de esta pantalla sensible.
@Suppress("TooManyFunctions", "LongParameterList")
class SettingsViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val biometricUnlockRepository: BiometricUnlockRepository,
    private val session: VaultSession,
    private val settings: SettingsRepository,
    private val cloudAccessRepository: CloudAccessRepository,
    private val vaultLifecycleRepository: VaultLifecycleRepository,
    private val googleIdTokenProvider: GoogleIdTokenProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private val _destinations = Channel<SettingsDestination>(Channel.BUFFERED)
    val destinations = _destinations.receiveAsFlow()
    private var sensitiveJob: Job? = null

    /** Ninguna pantalla sensible se compone si la sesión no está abierta
     * (`docs/architecture.md` §5). */
    val sessionState = session.state

    /** Preferencias de bloqueo automático (`docs/architecture.md` §5): nunca contienen
     * secretos, solo un minutaje y una bandera. */
    val lockTimeoutMinutes: StateFlow<Int> = settings.lockTimeoutMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DEFAULT_LOCK_TIMEOUT_MINUTES)
    val lockOnBackground: StateFlow<Boolean> = settings.lockOnBackground
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                biometricHardwareAvailable = biometricUnlockRepository.isBiometricHardwareAvailable(),
                biometricEnrolled = biometricUnlockRepository.hasBiometricEnrollment(),
                firebaseSignedIn = cloudAccessRepository.isAuthenticated
            )
        }
        viewModelScope.launch {
            session.state.collect { state ->
                if (state is SessionState.Locked) {
                    val leavingVault = _uiState.value.isLeavingVault
                    val operationInProgress = leavingVault && _uiState.value.isLoading
                    sensitiveJob?.cancel()
                    sensitiveJob = null
                    _uiState.value = SettingsUiState(
                        firebaseSignedIn = cloudAccessRepository.isAuthenticated,
                        isLoading = operationInProgress,
                        isLeavingVault = leavingVault
                    )
                }
            }
        }
    }

    /** Primer paso para activar el desbloqueo biométrico: exige la bóveda desbloqueada
     * (`SECURITY.md` §5, reautenticación implícita en la sesión activa). */
    suspend fun prepareBiometricEnrollmentCipher(): BiometricPrompt.CryptoObject? =
        biometricUnlockRepository.prepareEnrollmentCipher()

    fun cancelBiometricEnrollment() {
        viewModelScope.launch { biometricUnlockRepository.cancelPreparedEnrollment() }
    }

    fun completeBiometricEnrollment(cipher: Cipher) {
        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            biometricUnlockRepository.completeEnrollment(cipher).fold(
                onSuccess = {
                    settings.setBiometricEnabled(true)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "Desbloqueo biométrico activado.",
                        biometricEnrolled = true
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.toUserMessage())
                }
            )
        }
    }

    /** Exige la contraseña maestra como reautenticación (`SECURITY.md` §5). */
    fun disableBiometric(password: CharArray) {
        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            biometricUnlockRepository.disableBiometric(password).fold(
                onSuccess = {
                    settings.setBiometricEnabled(false)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        biometricEnrolled = false,
                        successMessage = "Desbloqueo biométrico desactivado."
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.toUserMessage())
                }
            )
        }
    }

    fun updateAutoLock(timeoutMinutes: Int? = null, lockOnBackground: Boolean? = null) {
        viewModelScope.launch {
            timeoutMinutes?.let { settings.setLockTimeoutMinutes(it) }
            lockOnBackground?.let { settings.setLockOnBackground(it) }
        }
    }

    fun changePassword(oldPassword: CharArray, newPassword: CharArray) {
        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            vaultRepository.changePassword(oldPassword, newPassword).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "Contraseña maestra cambiada. Tu frase de recuperación sigue siendo válida."
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.toUserMessage()
                    )
                }
            )
        }
    }

    /** Exige la contraseña maestra: es la reautenticación que pide
     * `SECURITY.md` §5 antes de regenerar la frase. */
    fun regenerateRecoveryPhrase(password: CharArray) {
        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            vaultRepository.regenerateRecoveryPhrase(password).fold(
                onSuccess = { words ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        recoveryPhrase = words,
                        successMessage = "Frase nueva generada. La anterior ya no sirve."
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.toUserMessage()
                    )
                }
            )
        }
    }

    fun hideRecoveryPhrase() {
        _uiState.value = _uiState.value.copy(recoveryPhrase = null)
    }

    fun lock() {
        sensitiveJob?.cancel()
        sensitiveJob = null
        _uiState.value = SettingsUiState(firebaseSignedIn = cloudAccessRepository.isAuthenticated)
        session.lock()
    }

    fun signOut(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isLeavingVault = true,
                errorMessage = null,
                recoveryPhrase = null
            )
            cloudAccessRepository.signOut().fold(
                onSuccess = {
                    googleIdTokenProvider.clearCredentialState(activity)
                    _destinations.send(SettingsDestination.CloudAccess)
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLeavingVault = false,
                        errorMessage = "No se pudo cerrar la sesión de Firebase. " +
                            "La bóveda quedó bloqueada."
                    )
                }
            )
        }
    }

    fun deleteVault(masterPassword: CharArray) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isLeavingVault = true,
                errorMessage = null,
                recoveryPhrase = null
            )
            vaultLifecycleRepository.deleteVault(masterPassword).fold(
                onSuccess = { _destinations.send(SettingsDestination.CloudAccess) },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLeavingVault = false,
                        errorMessage = error.toUserMessage()
                    )
                }
            )
        }
    }

    override fun onCleared() {
        sensitiveJob?.cancel()
        _uiState.value = SettingsUiState()
        super.onCleared()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val DEFAULT_LOCK_TIMEOUT_MINUTES = 5
    }
}
