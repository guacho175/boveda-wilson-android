package cl.bovedawilson.app.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.bovedawilson.app.ui.unlock.toUserMessage
import cl.bovedawilson.data.sync.repo.CloudAccessRepository
import cl.bovedawilson.data.sync.repo.VaultCreationRepository
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

/**
 * La frase de 24 palabras vive aquí **solo mientras la pantalla la muestra**: se entrega
 * una única vez tras crear la bóveda, no se persiste en ninguna parte y se descarta al
 * confirmar (ADR-011). Por eso el bloqueo y la cancelación la borran del estado.
 */
// Los campos modelan una sola máquina de estados; no se usa data class porque contiene
// recoveryPhrase y su representación generada expondría el secreto.
@Suppress("LongParameterList")
class CreateVaultUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val recoveryPhrase: List<String>? = null,
    val challengeIndices: List<Int> = emptyList(),
    val isVerifyingRecovery: Boolean = false,
    val vaultCreated: Boolean = false,
    val finished: Boolean = false,
    val cloudLinkRequired: Boolean = false
) {
    // No puede ser `data class`: recoveryPhrase no debe aparecer en toString/equals/hashCode.
    @Suppress("LongParameterList")
    fun copy(
        isLoading: Boolean = this.isLoading,
        errorMessage: String? = this.errorMessage,
        recoveryPhrase: List<String>? = this.recoveryPhrase,
        challengeIndices: List<Int> = this.challengeIndices,
        isVerifyingRecovery: Boolean = this.isVerifyingRecovery,
        vaultCreated: Boolean = this.vaultCreated,
        finished: Boolean = this.finished,
        cloudLinkRequired: Boolean = this.cloudLinkRequired
    ): CreateVaultUiState = CreateVaultUiState(
        isLoading = isLoading,
        errorMessage = errorMessage,
        recoveryPhrase = recoveryPhrase,
        challengeIndices = challengeIndices,
        isVerifyingRecovery = isVerifyingRecovery,
        vaultCreated = vaultCreated,
        finished = finished,
        cloudLinkRequired = cloudLinkRequired
    )

    override fun toString(): String = "CreateVaultUiState([REDACTED])"
}

@HiltViewModel
class CreateVaultViewModel @Inject constructor(
    private val vaultCreationRepository: VaultCreationRepository,
    private val cloudAccessRepository: CloudAccessRepository,
    private val session: VaultSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(CreateVaultUiState())
    val uiState: StateFlow<CreateVaultUiState> = _uiState.asStateFlow()

    val sessionState = session.state

    private val secureRandom = SecureRandom()
    private var creationJob: Job? = null

    fun createVault(password: CharArray) {
        creationJob?.cancel()
        creationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            vaultCreationRepository.begin(password).fold(
                onSuccess = { words ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        recoveryPhrase = words,
                        challengeIndices = selectChallengeIndices(words.size)
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

    fun startRecoveryVerification() {
        if (_uiState.value.recoveryPhrase != null) {
            _uiState.value = _uiState.value.copy(isVerifyingRecovery = true, errorMessage = null)
        }
    }

    fun verifyRecoveryWords(answers: List<String>) {
        val state = _uiState.value
        val phrase = state.recoveryPhrase ?: return
        val correct = answers.size == state.challengeIndices.size &&
            state.challengeIndices.indices.all { answerIndex ->
                val phraseIndex = state.challengeIndices[answerIndex]
                answers[answerIndex].trim().equals(phrase[phraseIndex], ignoreCase = true)
            }
        if (!correct) {
            _uiState.value = state.copy(
                errorMessage = "Las palabras no coinciden. Revisa tu copia e inténtalo otra vez."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, errorMessage = null)
            vaultCreationRepository.commit().fold(
                onSuccess = {
                    _uiState.value = CreateVaultUiState(vaultCreated = true, isLoading = true)
                    finishCreatedVault()
                },
                onFailure = { error ->
                    _uiState.value = state.copy(isLoading = false, errorMessage = error.toUserMessage())
                }
            )
        }
    }

    private suspend fun finishCreatedVault() {
        if (session.state.value !is SessionState.Unlocked) {
            _uiState.value = CreateVaultUiState(vaultCreated = true)
            return
        }
        if (!cloudAccessRepository.isAuthenticated) {
            _uiState.value = CreateVaultUiState(vaultCreated = true, finished = true)
            return
        }
        cloudAccessRepository.linkUnlockedLocalVault().fold(
            onSuccess = {
                _uiState.value = CreateVaultUiState(vaultCreated = true, finished = true)
            },
            onFailure = {
                _uiState.value = CreateVaultUiState(vaultCreated = true, cloudLinkRequired = true)
                session.lock()
            }
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** El bloqueo invalida inmediatamente la única copia mostrable de la frase. */
    fun onSessionLocked() {
        _uiState.value = _uiState.value.copy(recoveryPhrase = null)
    }

    /** Una interrupción antes de superar el desafío descarta la creación completa. */
    fun onAppBackgrounded() {
        val state = _uiState.value
        if (!state.vaultCreated && (state.isLoading || state.recoveryPhrase != null)) {
            creationJob?.cancel()
            vaultCreationRepository.cancel()
            _uiState.value = CreateVaultUiState()
        }
    }

    override fun onCleared() {
        creationJob?.cancel()
        if (!_uiState.value.vaultCreated) vaultCreationRepository.cancel()
        _uiState.value = CreateVaultUiState()
        super.onCleared()
    }

    private fun selectChallengeIndices(wordCount: Int): List<Int> {
        val selected = linkedSetOf<Int>()
        while (selected.size < RECOVERY_CHALLENGE_WORDS) selected += secureRandom.nextInt(wordCount)
        return selected.sorted()
    }

    private companion object {
        const val RECOVERY_CHALLENGE_WORDS = 3
    }
}
