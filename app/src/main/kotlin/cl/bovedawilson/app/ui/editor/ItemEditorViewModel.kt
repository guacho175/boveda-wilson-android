package cl.bovedawilson.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.bovedawilson.core.model.VaultItem
import cl.bovedawilson.data.sync.repo.ItemRepository
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * El contenido descifrado de la nota en edición vive aquí, en memoria del ViewModel, y se
 * borra en [onCleared]. No se usan los mecanismos de estado persistido de Android que
 * sobreviven a la muerte del proceso vía `Bundle` (`docs/architecture.md` §4); la
 * prueba de higiene G-69 los prohíbe por nombre.
 */
data class ItemEditorUiState(
    val item: VaultItem? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedSuccess: Boolean = false
)

const val NEW_ITEM_ID = "new"

@HiltViewModel
class ItemEditorViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val session: VaultSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(ItemEditorUiState())
    val uiState: StateFlow<ItemEditorUiState> = _uiState.asStateFlow()

    /** Ninguna pantalla sensible se compone si la sesión no está abierta
     * (`docs/architecture.md` §5): un bloqueo automático mientras se edita debe sacar
     * de esta pantalla, no dejar el contenido descifrado a la vista. */
    val sessionState = session.state

    private var loaded = false
    private var sensitiveJob: Job? = null

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                if (state is SessionState.Locked) {
                    sensitiveJob?.cancel()
                    sensitiveJob = null
                    loaded = false
                    _uiState.value = ItemEditorUiState()
                }
            }
        }
    }

    fun loadItem(itemId: String?) {
        if (loaded) return
        loaded = true

        if (itemId == null || itemId == NEW_ITEM_ID) {
            val now = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(
                item = VaultItem(
                    id = UUID.randomUUID().toString(),
                    title = "",
                    body = "",
                    tags = emptyList(),
                    fields = emptyList(),
                    createdAt = now,
                    updatedAt = now
                )
            )
            return
        }

        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val item = runCatching { itemRepository.getItem(itemId) }.getOrNull()
            if (session.state.value is SessionState.Unlocked) {
                _uiState.value = if (item == null) {
                    _uiState.value.copy(isLoading = false, errorMessage = "No se encontró la nota.")
                } else {
                    _uiState.value.copy(isLoading = false, item = item)
                }
            }
        }
    }

    fun saveItem(title: String, body: String) {
        val current = _uiState.value.item ?: return
        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            val updated = current.copy(
                title = title,
                body = body,
                updatedAt = System.currentTimeMillis()
            )
            val outcome = runCatching { itemRepository.saveItem(updated) }
            if (session.state.value is SessionState.Unlocked) {
                _uiState.value = if (outcome.isSuccess) {
                    _uiState.value.copy(isSaving = false, item = updated, savedSuccess = true)
                } else {
                    _uiState.value.copy(isSaving = false, errorMessage = "No se pudo guardar la nota.")
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        sensitiveJob?.cancel()
        _uiState.value = ItemEditorUiState()
        super.onCleared()
    }
}
