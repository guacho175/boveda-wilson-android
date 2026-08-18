package cl.bovedawilson.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.editor.ItemEditorViewModel
import cl.bovedawilson.app.ui.editor.NEW_ITEM_ID
import cl.bovedawilson.app.ui.util.copySensitiveText
import cl.bovedawilson.core.model.VaultItem
import cl.bovedawilson.data.sync.session.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorScreen(
    itemId: String?,
    navController: NavHostController,
    viewModel: ItemEditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()

    LaunchedEffect(itemId) { viewModel.loadItem(itemId) }

    LaunchedEffect(uiState.savedSuccess) {
        if (uiState.savedSuccess) navController.popBackStack()
    }

    // Un bloqueo automático mientras se edita no debe dejar el contenido descifrado
    // compuesto en pantalla (`docs/architecture.md` §5).
    LaunchedEffect(sessionState) {
        if (sessionState is SessionState.Locked) {
            navController.navigate(ROUTE_UNLOCK) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (itemId == NEW_ITEM_ID) "Nueva nota" else "Editar nota") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        val item = uiState.item
        when {
            uiState.isLoading || item == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            else -> EditorForm(
                item = item,
                isSaving = uiState.isSaving,
                errorMessage = uiState.errorMessage,
                onSave = viewModel::saveItem,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun EditorForm(
    item: VaultItem,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    // El contenido descifrado vive solo en el estado de composición, que muere con ella.
    // Los mecanismos de estado persistido que G-69 prohíbe sí sobrevivirían a la muerte
    // del proceso vía Bundle (`SECURITY.md` §3).
    var title by remember(item.id) { mutableStateOf(item.title) }
    var body by remember(item.id) { mutableStateOf(item.body) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Título") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving
        )

        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Contenido") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving,
            trailingIcon = {
                // Copiar exige esta acción explícita: nada se copia solo
                // (`SECURITY.md` §5). Se marca sensible y se borra sola.
                IconButton(
                    onClick = { copySensitiveText(context, body) },
                    enabled = body.isNotBlank()
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar contenido")
                }
            }
        )

        Button(
            onClick = { onSave(title, body) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSaving
        ) {
            if (isSaving) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Guardar")
        }

        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}
