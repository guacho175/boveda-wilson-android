package cl.bovedawilson.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.components.SecureConfirmation
import cl.bovedawilson.app.ui.items.ItemsViewModel
import cl.bovedawilson.core.model.VaultItem
import cl.bovedawilson.data.sync.session.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsListScreen(navController: NavHostController, viewModel: ItemsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()

    // Ninguna pantalla sensible se compone con la sesión cerrada
    // (`docs/architecture.md` §5): al bloquear se vuelve al desbloqueo.
    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Locked) {
            navController.navigate(ROUTE_UNLOCK) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Al volver del editor la lista se relee para reflejar lo recién guardado.
    LifecycleResumeEffect(Unit) {
        viewModel.loadItems()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bóveda abierta") },
                actions = {
                    IconButton(onClick = { navController.navigate(ROUTE_SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configuración")
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Bloquear ahora")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate(routeForNewItem()) }) {
                Icon(Icons.Filled.Add, contentDescription = "Nueva nota")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Buscar") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            val visible = uiState.visibleItems
            when {
                uiState.isLoading -> CenteredBox { CircularProgressIndicator() }
                visible.isEmpty() && uiState.query.isNotBlank() ->
                    CenteredBox { Text("Ninguna nota coincide con la búsqueda.") }
                visible.isEmpty() ->
                    CenteredBox { Text("Todavía no hay notas. Crea la primera con el botón +.") }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { item ->
                        ItemListItemCard(
                            item = item,
                            onDelete = { viewModel.deleteItem(item.id) },
                            onSelect = { navController.navigate(routeForItem(item.id)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemListItemCard(item: VaultItem, onDelete: () -> Unit, onSelect: () -> Unit) {
    var showDeleteConfirm by remember(item.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { "(sin título)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.body.isNotBlank()) {
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Eliminar")
            }
        }
    }

    if (showDeleteConfirm) {
        SecureConfirmation(
            title = "¿Eliminar esta nota?",
            text = "Esta acción no se puede deshacer.",
            confirmLabel = "Eliminar",
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismissRequest = { showDeleteConfirm = false }
        )
    }
}
