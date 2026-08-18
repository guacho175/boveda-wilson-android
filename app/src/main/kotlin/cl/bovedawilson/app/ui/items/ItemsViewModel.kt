package cl.bovedawilson.app.ui.items

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
import javax.inject.Inject

data class ItemsUiState(
    val items: List<VaultItem> = emptyList(),
    val isLoading: Boolean = false,
    val query: String = ""
) {
    /** Búsqueda en memoria sobre el contenido ya descifrado: no existe índice persistente
     * (`docs/architecture.md` §7). */
    val visibleItems: List<VaultItem>
        get() = if (query.isBlank()) {
            items
        } else {
            items.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.body.contains(query, ignoreCase = true) ||
                    it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
            }
        }
}

@HiltViewModel
class ItemsViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val session: VaultSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(ItemsUiState())
    val uiState: StateFlow<ItemsUiState> = _uiState.asStateFlow()

    val sessionState = session.state

    private var sensitiveJob: Job? = null

    init {
        viewModelScope.launch {
            session.state.collect { state ->
                if (state is SessionState.Locked) {
                    sensitiveJob?.cancel()
                    sensitiveJob = null
                    _uiState.value = ItemsUiState()
                }
            }
        }
        loadItems()
    }

    fun loadItems() {
        if (session.state.value !is SessionState.Unlocked) return
        sensitiveJob?.cancel()
        sensitiveJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val items = runCatching { itemRepository.listItems() }.getOrDefault(emptyList())
            if (session.state.value is SessionState.Unlocked) {
                _uiState.value = _uiState.value.copy(items = items, isLoading = false)
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            runCatching { itemRepository.deleteItem(itemId) }
            loadItems()
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun lock() {
        session.lock()
        // El contenido descifrado no sobrevive al bloqueo (`SECURITY.md` §5).
        _uiState.value = ItemsUiState()
    }

    override fun onCleared() {
        sensitiveJob?.cancel()
        _uiState.value = ItemsUiState()
        super.onCleared()
    }
}
