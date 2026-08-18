package cl.bovedawilson.app.ui.cloud

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.bovedawilson.app.ui.unlock.toUserMessage
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.data.sync.repo.CloudAccessRepository
import cl.bovedawilson.data.sync.repo.CloudLanding
import cl.bovedawilson.data.sync.repo.RemoteVaultOption
import cl.bovedawilson.data.sync.repo.VaultLifecycleRepository
import cl.bovedawilson.data.sync.repo.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CloudAuthMode { SignIn, SignUp }

enum class CloudDestination { CreateVault, UnlockVault, Items }

data class CloudAccessUiState(
    val isLoading: Boolean = true,
    val isConfigured: Boolean = true,
    val authMode: CloudAuthMode = CloudAuthMode.SignIn,
    val showAuthentication: Boolean = false,
    val localLinkRequired: Boolean = false,
    val ownerConflict: Boolean = false,
    val deletionPending: Boolean = false,
    val remoteOptions: List<RemoteVaultOption> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
@Suppress("TooManyFunctions")
class CloudAccessViewModel @Inject constructor(
    private val cloudAccess: CloudAccessRepository,
    private val vaultRepository: VaultRepository,
    private val vaultLifecycleRepository: VaultLifecycleRepository,
    private val googleIdTokenProvider: GoogleIdTokenProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CloudAccessUiState())
    val uiState: StateFlow<CloudAccessUiState> = _uiState.asStateFlow()

    private val _destinations = Channel<CloudDestination>(capacity = Channel.BUFFERED)
    val destinations = _destinations.receiveAsFlow()

    init {
        resume()
    }

    fun setAuthMode(mode: CloudAuthMode) {
        _uiState.value = _uiState.value.copy(authMode = mode, errorMessage = null)
    }

    fun signIn(email: String, password: CharArray) {
        authenticate { cloudAccess.signIn(email, password) }
    }

    fun signUp(email: String, password: CharArray) {
        authenticate { cloudAccess.signUp(email, password) }
    }

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val token = googleIdTokenProvider.request(activity)
                cloudAccess.signInWithGoogleIdToken(token).fold(
                    onSuccess = { landing -> handleAuthenticatedLanding(landing) },
                    onFailure = ::showError,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
                showError(AppError.OperationFailed)
            }
        }
    }

    private suspend fun handleAuthenticatedLanding(landing: CloudLanding) {
        if (vaultLifecycleRepository.hasPendingDeletion()) {
            vaultLifecycleRepository.resumePendingDeletion().fold(
                onSuccess = { routeCurrentState() },
                onFailure = ::showPendingDeletion,
            )
        } else {
            handleLanding(landing)
        }
    }

    fun selectRemoteVault(vaultId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            cloudAccess.selectRemoteVault(vaultId).fold(
                onSuccess = {
                    _uiState.value = CloudAccessUiState(isLoading = false, isConfigured = true)
                    _destinations.send(CloudDestination.UnlockVault)
                },
                onFailure = ::showError
            )
        }
    }

    /** La contraseña maestra solo reautentica localmente. Firebase recibe únicamente
     * metadata ya envuelta después de que la sesión haya sido abierta con éxito. */
    fun linkLocalVault(password: CharArray) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val unlock = vaultRepository.unlockVault(password)) {
                is AppResult.Failure -> showError(unlock.error)
                is AppResult.Success -> cloudAccess.linkUnlockedLocalVault().fold(
                    onSuccess = {
                        _uiState.value = CloudAccessUiState(isLoading = false, isConfigured = true)
                        _destinations.send(CloudDestination.Items)
                    },
                    onFailure = { error ->
                        vaultRepository.lockVault()
                        showError(error)
                    }
                )
            }
        }
    }

    fun signOut(activity: Activity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            cloudAccess.signOut().fold(
                onSuccess = {
                    googleIdTokenProvider.clearCredentialState(activity)
                    _uiState.value = CloudAccessUiState(
                        isLoading = false,
                        isConfigured = true,
                        showAuthentication = true,
                        deletionPending = vaultLifecycleRepository.hasPendingDeletion()
                    )
                },
                onFailure = ::showError
            )
        }
    }

    private fun resume() {
        viewModelScope.launch {
            if (vaultLifecycleRepository.hasPendingDeletion()) {
                when (val resumed = vaultLifecycleRepository.resumePendingDeletion()) {
                    is AppResult.Failure -> {
                        showPendingDeletion(resumed.error)
                        return@launch
                    }
                    is AppResult.Success -> Unit
                }
            }
            routeCurrentState()
        }
    }

    private suspend fun routeCurrentState() {
        if (!cloudAccess.isConfigured) {
            val destination = if (cloudAccess.hasLocalVault()) {
                CloudDestination.UnlockVault
            } else {
                CloudDestination.CreateVault
            }
            _uiState.value = CloudAccessUiState(isLoading = false, isConfigured = false)
            _destinations.send(destination)
            return
        }

        cloudAccess.resumeAuthenticatedSession().fold(
            onSuccess = { landing ->
                if (landing == null) {
                    _uiState.value = CloudAccessUiState(
                        isLoading = false,
                        isConfigured = true,
                        showAuthentication = true
                    )
                } else {
                    handleLanding(landing)
                }
            },
            onFailure = ::showError
        )
    }

    private fun authenticate(action: suspend () -> AppResult<CloudLanding, AppError>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            action().fold(
                onSuccess = { landing ->
                    if (vaultLifecycleRepository.hasPendingDeletion()) {
                        vaultLifecycleRepository.resumePendingDeletion().fold(
                            onSuccess = { routeCurrentState() },
                            onFailure = ::showPendingDeletion
                        )
                    } else {
                        handleLanding(landing)
                    }
                },
                onFailure = ::showError
            )
        }
    }

    private fun showPendingDeletion(error: AppError) {
        _uiState.value = CloudAccessUiState(
            isLoading = false,
            isConfigured = cloudAccess.isConfigured,
            showAuthentication = cloudAccess.isConfigured,
            deletionPending = true,
            errorMessage = error.toUserMessage()
        )
    }

    private fun handleLanding(landing: CloudLanding) {
        when (landing) {
            CloudLanding.CreateVault -> _destinations.trySend(CloudDestination.CreateVault)
            CloudLanding.LocalVault -> _destinations.trySend(CloudDestination.UnlockVault)
            CloudLanding.LocalLinkRequired -> _uiState.value = CloudAccessUiState(
                isLoading = false,
                isConfigured = true,
                localLinkRequired = true
            )
            CloudLanding.OwnerConflict -> _uiState.value = CloudAccessUiState(
                isLoading = false,
                isConfigured = true,
                ownerConflict = true
            )
            is CloudLanding.SelectVault -> _uiState.value = CloudAccessUiState(
                isLoading = false,
                isConfigured = true,
                remoteOptions = landing.options
            )
        }
    }

    private fun showError(error: AppError) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = error.toUserMessage()
        )
    }
}
