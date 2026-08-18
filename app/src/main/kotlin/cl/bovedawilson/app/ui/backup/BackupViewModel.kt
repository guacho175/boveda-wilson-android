package cl.bovedawilson.app.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.bovedawilson.app.ui.unlock.toUserMessage
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.data.sync.repo.BackupRepository
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

class BackupUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val recoveryPhrase: List<String>? = null,
) {
    override fun toString(): String =
        "BackupUiState(isLoading=$isLoading, hasError=${errorMessage != null}, " +
            "hasSuccess=${successMessage != null}, recoveryPhrase=[REDACTED])"
}

internal class BackupLifecycleGate {
    private var generation = 0L

    @Synchronized
    fun beginOperation(): Long = ++generation

    @Synchronized
    fun invalidateOperations() {
        generation++
    }

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = candidate == generation
}

@HiltViewModel
@Suppress("TooManyFunctions")
class BackupViewModel @Inject constructor(
    private val repository: BackupRepository,
    private val session: VaultSession,
    private val pendingSafStore: PendingBackupSafStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()
    private val _unlockDestinations = Channel<Unit>(capacity = Channel.BUFFERED)
    val unlockDestinations = _unlockDestinations.receiveAsFlow()
    val pendingSaf = pendingSafStore.pending
    private var restoreInProgress = false
    private var externalPickerActive = false
    private val lifecycleGate = BackupLifecycleGate()

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                if (state is SessionState.Locked && shouldLeaveLockedSurface()) {
                    clearSensitiveState()
                    _unlockDestinations.trySend(Unit)
                }
            }
        }
    }

    private fun shouldLeaveLockedSurface(): Boolean =
        !restoreInProgress && !externalPickerActive && _uiState.value.recoveryPhrase == null

    fun beginExternalPicker() {
        externalPickerActive = true
    }

    fun completeExternalPicker(action: BackupSafAction, uri: android.net.Uri?) {
        externalPickerActive = false
        if (uri != null) pendingSafStore.set(action, uri)
        if (session.state.value is SessionState.Locked) _unlockDestinations.trySend(Unit)
    }

    fun consumePendingSaf(action: BackupSafAction) = pendingSafStore.clear(action)

    fun export(outputFactory: (() -> OutputStream?)?, password: CharArray) {
        if (outputFactory == null) {
            password.fill('\u0000')
            showError(AppError.OperationFailed)
            return
        }
        val operation = lifecycleGate.beginOperation()
        viewModelScope.launch {
            setLoading()
            val result = repository.exportVault(outputFactory, password)
            if (!lifecycleGate.isCurrent(operation)) return@launch
            result.fold(
                onSuccess = { _uiState.value = BackupUiState(successMessage = "Respaldo cifrado exportado.") },
                onFailure = ::showError,
            )
        }
    }

    fun restoreWithPassword(
        input: InputStream?,
        password: CharArray,
        currentVaultPassword: CharArray,
        replacementConfirmation: CharArray,
    ) {
        if (input == null) {
            password.fill('\u0000')
            currentVaultPassword.fill('\u0000')
            replacementConfirmation.fill('\u0000')
            showError(AppError.OperationFailed)
            return
        }
        val operation = lifecycleGate.beginOperation()
        viewModelScope.launch {
            restoreInProgress = true
            setLoading()
            val result = repository.restoreWithPassword(
                input,
                password,
                currentVaultPassword,
                replacementConfirmation,
            )
            if (!lifecycleGate.isCurrent(operation)) return@launch
            handleRestore(result)
            restoreInProgress = false
        }
    }

    fun restoreWithRecovery(
        input: InputStream?,
        phrase: List<String>,
        newPassword: CharArray,
        currentVaultPassword: CharArray,
        replacementConfirmation: CharArray,
    ) {
        if (input == null) {
            newPassword.fill('\u0000')
            currentVaultPassword.fill('\u0000')
            replacementConfirmation.fill('\u0000')
            showError(AppError.OperationFailed)
            return
        }
        val operation = lifecycleGate.beginOperation()
        viewModelScope.launch {
            restoreInProgress = true
            setLoading()
            val result = repository.restoreWithRecovery(
                input,
                phrase,
                newPassword,
                currentVaultPassword,
                replacementConfirmation,
            )
            if (!lifecycleGate.isCurrent(operation)) return@launch
            handleRestore(result)
            restoreInProgress = false
        }
    }

    fun clearRecoveryPhrase() {
        clearSensitiveState()
        _unlockDestinations.trySend(Unit)
    }

    fun leaveSensitiveSurface() {
        clearSensitiveState()
        if (session.state.value is SessionState.Locked) _unlockDestinations.trySend(Unit)
    }

    fun onHostStopped() {
        lifecycleGate.invalidateOperations()
        restoreInProgress = false
        clearSensitiveState()
        if (!externalPickerActive) _unlockDestinations.trySend(Unit)
    }

    fun publishRestoredBackup(input: InputStream?) {
        if (input == null) {
            showError(AppError.OperationFailed)
            return
        }
        val operation = lifecycleGate.beginOperation()
        viewModelScope.launch {
            setLoading()
            val result = repository.publishRestoredBackup(input)
            if (!lifecycleGate.isCurrent(operation)) return@launch
            result.fold(
                onSuccess = {
                    _uiState.value = BackupUiState(
                        successMessage = "Respaldo cifrado publicado sin sobrescribir cambios remotos.",
                    )
                },
                onFailure = ::showError,
            )
        }
    }

    private fun handleRestore(
        result: AppResult<cl.bovedawilson.data.sync.repo.BackupRestoreResult, AppError>
    ) {
        result.fold(
            onSuccess = { restored ->
                _uiState.value = BackupUiState(
                    successMessage =
                    "Respaldo restaurado localmente. La sincronización remota " +
                        "requiere revisión explícita.",
                    recoveryPhrase = restored.recoveryPhrase,
                )
                if (restored.recoveryPhrase == null) _unlockDestinations.trySend(Unit)
            },
            onFailure = ::showError,
        )
    }

    private fun setLoading() {
        _uiState.value = BackupUiState(isLoading = true)
    }

    private fun showError(error: AppError) {
        _uiState.value = BackupUiState(errorMessage = error.toUserMessage())
    }

    private fun clearSensitiveState() {
        _uiState.value = BackupUiState()
    }

    override fun onCleared() {
        clearSensitiveState()
        super.onCleared()
    }
}
