package cl.bovedawilson.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.backup.BackupSafAction
import cl.bovedawilson.app.ui.backup.BackupViewModel
import cl.bovedawilson.app.ui.components.SecurePasswordField
import cl.bovedawilson.app.ui.components.SecurePasswordState
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.data.sync.repo.BackupRepository

private const val RECOVERY_WORD_COUNT = 24

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun BackupScreen(navController: NavHostController, viewModel: BackupViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    val lifecycleOwner = LocalLifecycleOwner.current
    val pendingSaf by viewModel.pendingSaf.collectAsState()
    val exportUri = pendingSaf?.takeIf { it.action == BackupSafAction.Export }?.uri
    val restoreUri = pendingSaf?.takeIf { it.action == BackupSafAction.Restore }?.uri
    val publicationUri = pendingSaf?.takeIf { it.action == BackupSafAction.Publish }?.uri
    var restoreWithPhrase by remember { mutableStateOf(false) }
    val exportPassword = remember { SecurePasswordState() }
    val restorePassword = remember { SecurePasswordState() }
    val newMasterPassword = remember { SecurePasswordState() }
    val newMasterPasswordConfirmation = remember { SecurePasswordState() }
    val currentVaultPassword = remember { SecurePasswordState() }
    var replacementConfirmation by remember { mutableStateOf("") }
    val words = remember { List(RECOVERY_WORD_COUNT) { SecurePasswordState() } }
    fun clearInputBuffers() {
        exportPassword.clear()
        restorePassword.clear()
        newMasterPassword.clear()
        newMasterPasswordConfirmation.clear()
        currentVaultPassword.clear()
        replacementConfirmation = ""
        words.forEach(SecurePasswordState::clear)
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { viewModel.completeExternalPicker(BackupSafAction.Export, it) }
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { viewModel.completeExternalPicker(BackupSafAction.Restore, it) }
    val publicationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { viewModel.completeExternalPicker(BackupSafAction.Publish, it) }

    LaunchedEffect(Unit) {
        viewModel.unlockDestinations.collect {
            navController.navigate(ROUTE_UNLOCK) {
                popUpTo(ROUTE_BACKUP) { inclusive = true }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clearInputBuffers()
            viewModel.leaveSensitiveSurface()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                clearInputBuffers()
                viewModel.onHostStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Respaldo cifrado") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "El archivo contiene únicamente ciphertext y parámetros públicos. Nunca incluye " +
                    "la biometría local ni contenido descifrado.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "La exportación requiere reautenticación. Restaurar reemplaza la copia local y " +
                    "no publica nada en Firebase automáticamente.",
                style = MaterialTheme.typography.bodySmall
            )

            Text("Exportar", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = {
                    viewModel.beginExternalPicker()
                    exportPicker.launch("boveda-wilson.bwvault")
                },
                enabled = !uiState.isLoading && uiState.recoveryPhrase == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (exportUri == null) "Elegir destino" else "Destino elegido") }
            SecurePasswordField(
                label = "Contraseña maestra para reautenticar",
                state = exportPassword,
                enabled = !uiState.isLoading && uiState.recoveryPhrase == null,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val password = exportPassword.takeCharsAndClear()
                    viewModel.export({ exportUri?.let(resolver::openOutputStream) }, password)
                    viewModel.consumePendingSaf(BackupSafAction.Export)
                },
                enabled = exportUri != null && exportPassword.isNotEmpty && !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Exportar respaldo cifrado") }

            Text("Restaurar", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Si el archivo pertenece a otra bóveda, se exigirá la contraseña de la bóveda " +
                    "local y escribir ${BackupRepository.REPLACEMENT_CONFIRMATION}. Sin ambas, no se reemplaza nada.",
                style = MaterialTheme.typography.bodySmall,
            )
            SecurePasswordField(
                label = "Contraseña de la bóveda local (solo si es distinta)",
                state = currentVaultPassword,
                enabled = !uiState.isLoading && uiState.recoveryPhrase == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = replacementConfirmation,
                onValueChange = { replacementConfirmation = it.uppercase().take(16) },
                label = { Text("Confirmación para reemplazar otra bóveda") },
                singleLine = true,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    viewModel.beginExternalPicker()
                    restorePicker.launch(arrayOf("application/json", "application/octet-stream"))
                },
                enabled = !uiState.isLoading && uiState.recoveryPhrase == null,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (restoreUri == null) "Elegir respaldo" else "Respaldo elegido") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !restoreWithPhrase,
                    onClick = { restoreWithPhrase = false },
                    label = { Text("Usar contraseña") }
                )
                FilterChip(
                    selected = restoreWithPhrase,
                    onClick = { restoreWithPhrase = true },
                    label = { Text("Usar frase") }
                )
            }

            if (!restoreWithPhrase) {
                SecurePasswordField(
                    label = "Contraseña del respaldo",
                    state = restorePassword,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val password = restorePassword.takeCharsAndClear()
                        viewModel.restoreWithPassword(
                            restoreUri?.let(resolver::openInputStream),
                            password,
                            currentVaultPassword.takeCharsAndClear(),
                            replacementConfirmation.toCharArray(),
                        )
                        replacementConfirmation = ""
                        viewModel.consumePendingSaf(BackupSafAction.Restore)
                    },
                    enabled = restoreUri != null && restorePassword.isNotEmpty && !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restaurar con contraseña") }
            } else {
                Text("La frase se usa solo en memoria y no se guarda.", style = MaterialTheme.typography.bodySmall)
                words.forEachIndexed { index, word ->
                    SecurePasswordField(
                        label = "Palabra ${index + 1}",
                        state = word,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SecurePasswordField(
                    label = "Contraseña maestra nueva",
                    state = newMasterPassword,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                SecurePasswordField(
                    label = "Confirmar contraseña maestra nueva",
                    state = newMasterPasswordConfirmation,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val phraseBuffers = words.map(SecurePasswordState::takeCharsAndClear)
                        val phrase = phraseBuffers.map(CharArray::concatToString)
                        phraseBuffers.forEach(Wipe::chars)
                        viewModel.restoreWithRecovery(
                            restoreUri?.let(resolver::openInputStream),
                            phrase,
                            newMasterPassword.takeCharsAndClear(),
                            currentVaultPassword.takeCharsAndClear(),
                            replacementConfirmation.toCharArray(),
                        )
                        newMasterPasswordConfirmation.clear()
                        replacementConfirmation = ""
                        viewModel.consumePendingSaf(BackupSafAction.Restore)
                    },
                    enabled = restoreUri != null && words.all(SecurePasswordState::isNotEmpty) &&
                        newMasterPassword.isNotEmpty &&
                        newMasterPasswordConfirmation.isNotEmpty &&
                        newMasterPassword.contentEquals(newMasterPasswordConfirmation) &&
                        !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restaurar con frase") }
            }

            if (uiState.isLoading) CircularProgressIndicator()
            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            uiState.recoveryPhrase?.let { phrase ->
                Text(
                    "Anota la nueva frase en papel. No se volverá a mostrar.",
                    color = MaterialTheme.colorScheme.error
                )
                phrase.forEachIndexed { index, word -> Text("${index + 1}. $word") }
                Button(onClick = viewModel::clearRecoveryPhrase) { Text("Ya la anoté") }
            }

            Text("Publicación remota", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Selecciona nuevamente el respaldo restaurado. Si Firebase contiene una " +
                    "versión diferente, la publicación se bloquea sin sobrescribirla.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    viewModel.beginExternalPicker()
                    publicationPicker.launch(arrayOf("application/json", "application/octet-stream"))
                },
                enabled = !uiState.isLoading && uiState.recoveryPhrase == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (publicationUri == null) "Elegir línea base" else "Línea base elegida") }
            Button(
                onClick = {
                    viewModel.publishRestoredBackup(publicationUri?.let(resolver::openInputStream))
                    viewModel.consumePendingSaf(BackupSafAction.Publish)
                },
                enabled = publicationUri != null && !uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Publicar respaldo cifrado") }
        }
    }
}
