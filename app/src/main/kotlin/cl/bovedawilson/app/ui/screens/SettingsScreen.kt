package cl.bovedawilson.app.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.biometric.BiometricPromptCopy
import cl.bovedawilson.app.ui.biometric.showBiometricPrompt
import cl.bovedawilson.app.ui.components.SecureConfirmation
import cl.bovedawilson.app.ui.components.SecurePasswordField
import cl.bovedawilson.app.ui.components.SecurePasswordState
import cl.bovedawilson.app.ui.settings.SettingsDestination
import cl.bovedawilson.app.ui.settings.SettingsViewModel
import cl.bovedawilson.data.sync.session.SessionState
import kotlinx.coroutines.launch
import javax.crypto.Cipher

private val LOCK_TIMEOUT_PRESETS_MINUTES = listOf(1, 5, 15, 30)

private const val MIN_PASSWORD_LENGTH = 12
private const val DELETE_CONFIRMATION = "ELIMINAR"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController, viewModel: SettingsViewModel = hiltViewModel()) {
    val activity = requireNotNull(LocalActivity.current)
    val uiState by viewModel.uiState.collectAsState()
    val lockTimeoutMinutes by viewModel.lockTimeoutMinutes.collectAsState()
    val lockOnBackground by viewModel.lockOnBackground.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()

    // Un bloqueo automático mientras se está en Configuración no debe dejar esta
    // pantalla compuesta con la sesión cerrada (`docs/architecture.md` §5).
    LaunchedEffect(sessionState, uiState.isLeavingVault) {
        if (
            sessionState is SessionState.Locked &&
            !uiState.isLeavingVault &&
            uiState.errorMessage == null
        ) {
            navController.navigate(ROUTE_UNLOCK) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.destinations.collect { destination ->
            when (destination) {
                SettingsDestination.CloudAccess -> navController.navigate(ROUTE_CLOUD_ACCESS) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    // Al bloquear, se dispone inmediatamente todo el subárbol con campos `remember`
    // sensibles. Durante sign-out/purga solo queda una superficie neutra de progreso.
    if (sessionState is SessionState.Locked) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.isLeavingVault) {
                CircularProgressIndicator()
            } else if (uiState.errorMessage != null) {
                Text(uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = {
                        navController.navigate(ROUTE_CLOUD_ACCESS) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                ) {
                    Text("Volver al acceso")
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
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
            AutoLockSection(
                timeoutMinutes = lockTimeoutMinutes,
                lockOnBackground = lockOnBackground,
                onTimeoutChange = { viewModel.updateAutoLock(timeoutMinutes = it) },
                onLockOnBackgroundChange = { viewModel.updateAutoLock(lockOnBackground = it) }
            )

            HorizontalDivider()

            BiometricSection(
                state = BiometricSectionState(
                    hardwareAvailable = uiState.biometricHardwareAvailable,
                    enrolled = uiState.biometricEnrolled,
                    isLoading = uiState.isLoading
                ),
                onPrepareEnroll = viewModel::prepareBiometricEnrollmentCipher,
                onEnroll = viewModel::completeBiometricEnrollment,
                onCancelEnroll = viewModel::cancelBiometricEnrollment,
                onDisable = viewModel::disableBiometric
            )

            HorizontalDivider()

            ChangePasswordSection(uiState.isLoading, viewModel::changePassword)

            HorizontalDivider()

            RegenerateRecoverySection(
                isLoading = uiState.isLoading,
                phrase = uiState.recoveryPhrase,
                onRegenerate = viewModel::regenerateRecoveryPhrase,
                onAcknowledge = viewModel::hideRecoveryPhrase
            )

            HorizontalDivider()

            Text("Respaldo", style = MaterialTheme.typography.headlineSmall)
            Button(
                onClick = { navController.navigate(ROUTE_BACKUP) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Exportar o restaurar respaldo cifrado")
            }

            HorizontalDivider()

            AccountSection(
                firebaseSignedIn = uiState.firebaseSignedIn,
                isLoading = uiState.isLoading,
                onSignOut = { viewModel.signOut(activity) }
            )

            HorizontalDivider()

            DeleteVaultSection(
                isLoading = uiState.isLoading,
                onDelete = viewModel::deleteVault
            )

            HorizontalDivider()

            Button(
                onClick = {
                    viewModel.lock()
                    navController.navigate(ROUTE_UNLOCK) { popUpTo(0) { inclusive = true } }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Bloquear la bóveda ahora")
            }

            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            uiState.successMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun AccountSection(
    firebaseSignedIn: Boolean,
    isLoading: Boolean,
    onSignOut: () -> Unit
) {
    if (!firebaseSignedIn) return
    var showConfirmation by remember { mutableStateOf(false) }
    Text("Cuenta Firebase", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Cerrar sesion bloquea la boveda y conserva todos los datos cifrados locales. " +
            "La cuenta no puede descifrar el contenido.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(
        onClick = { showConfirmation = true },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Cerrar sesion de Firebase") }

    if (showConfirmation) {
        SecureConfirmation(
            title = "Cerrar sesion",
            text = "La boveda local se conservara cifrada y quedara bloqueada.",
            confirmLabel = "Cerrar sesion",
            onConfirm = {
                showConfirmation = false
                onSignOut()
            },
            onDismissRequest = { showConfirmation = false }
        )
    }
}

@Composable
private fun DeleteVaultSection(
    isLoading: Boolean,
    onDelete: (CharArray) -> Unit
) {
    val password = remember { SecurePasswordState() }
    var typedConfirmation by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    Text("Zona de peligro", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Eliminar la boveda borra el ciphertext local y, si esta vinculada, tambien el " +
            "ciphertext remoto. La operacion es irreversible. Soporte, Google y Firebase " +
            "no pueden recuperar la boveda.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
    SecurePasswordField(
        label = "Contraseña maestra",
        state = password,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = typedConfirmation,
        onValueChange = { typedConfirmation = it },
        label = { Text("Escribe $DELETE_CONFIRMATION") },
        singleLine = true,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { showConfirmation = true },
        enabled = !isLoading && password.isNotEmpty && typedConfirmation == DELETE_CONFIRMATION,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Eliminar la boveda permanentemente") }

    if (showConfirmation) {
        SecureConfirmation(
            title = "Eliminacion irreversible",
            text = "Se cancelara la sincronizacion, se purgaran los datos cifrados y se " +
                "invalidara el acceso biometrico local.",
            confirmLabel = "Eliminar permanentemente",
            onConfirm = {
                showConfirmation = false
                typedConfirmation = ""
                onDelete(password.takeCharsAndClear())
            },
            onDismissRequest = { showConfirmation = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoLockSection(
    timeoutMinutes: Int,
    lockOnBackground: Boolean,
    onTimeoutChange: (Int) -> Unit,
    onLockOnBackgroundChange: (Boolean) -> Unit
) {
    Text("Bloqueo automático", style = MaterialTheme.typography.headlineSmall)
    Text(
        "La bóveda se bloquea sola tras un periodo de inactividad.",
        style = MaterialTheme.typography.bodySmall
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LOCK_TIMEOUT_PRESETS_MINUTES.forEach { minutes ->
            FilterChip(
                selected = minutes == timeoutMinutes,
                onClick = { onTimeoutChange(minutes) },
                label = { Text("$minutes min") }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Bloquear al pasar a segundo plano", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = lockOnBackground, onCheckedChange = onLockOnBackgroundChange)
    }
}

@Composable
private fun BiometricSection(
    state: BiometricSectionState,
    onPrepareEnroll: suspend () -> BiometricPrompt.CryptoObject?,
    onEnroll: (Cipher) -> Unit,
    onCancelEnroll: () -> Unit,
    onDisable: (CharArray) -> Unit
) {
    val activity = LocalActivity.current as FragmentActivity
    val coroutineScope = rememberCoroutineScope()
    var showDisableConfirm by remember { mutableStateOf(false) }
    val disablePassword = remember { SecurePasswordState() }

    Text("Desbloqueo biométrico", style = MaterialTheme.typography.headlineSmall)

    if (!state.hardwareAvailable) {
        Text(
            "Este dispositivo no tiene biometría fuerte disponible.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    Text(
        "No sustituye a la contraseña maestra ni a la frase de recuperación: solo es un " +
            "atajo local. Si el dispositivo se pierde, sigue haciendo falta la contraseña.",
        style = MaterialTheme.typography.bodySmall
    )

    if (state.enrolled) {
        SecurePasswordField(
            label = "Contraseña maestra",
            state = disablePassword,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { showDisableConfirm = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading && disablePassword.isNotEmpty
        ) { Text("Desactivar desbloqueo biométrico") }
    } else {
        Button(
            onClick = {
                coroutineScope.launch {
                    val cryptoObject = onPrepareEnroll() ?: return@launch
                    showBiometricPrompt(
                        activity = activity,
                        cryptoObject = cryptoObject,
                        copy = BiometricPromptCopy("Activar desbloqueo biométrico", "Cancelar"),
                        onSuccess = onEnroll,
                        onError = { onCancelEnroll() }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading
        ) { Text("Activar desbloqueo biométrico") }
    }

    if (showDisableConfirm) {
        SecureConfirmation(
            title = "¿Desactivar el desbloqueo biométrico?",
            text = "Tendrás que usar la contraseña maestra o la frase de recuperación.",
            confirmLabel = "Desactivar",
            onConfirm = {
                showDisableConfirm = false
                onDisable(disablePassword.takeCharsAndClear())
            },
            onDismissRequest = { showDisableConfirm = false }
        )
    }
}

@Composable
private fun ChangePasswordSection(isLoading: Boolean, onChange: (CharArray, CharArray) -> Unit) {
    val current = remember { SecurePasswordState() }
    val replacement = remember { SecurePasswordState() }
    val confirmation = remember { SecurePasswordState() }

    Text("Cambiar contraseña maestra", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Las notas siguen accesibles y tu frase de recuperación actual sigue sirviendo.",
        style = MaterialTheme.typography.bodySmall
    )

    SecurePasswordField("Contraseña actual", current, !isLoading, Modifier.fillMaxWidth())
    SecurePasswordField("Nueva contraseña", replacement, !isLoading, Modifier.fillMaxWidth())
    SecurePasswordField("Repite la nueva", confirmation, !isLoading, Modifier.fillMaxWidth())

    if (confirmation.isNotEmpty && !replacement.contentEquals(confirmation)) {
        Text("Las contraseñas nuevas no coinciden.", color = MaterialTheme.colorScheme.error)
    }

    Button(
        onClick = {
            val currentChars = current.takeCharsAndClear()
            val replacementChars = replacement.takeCharsAndClear()
            confirmation.clear()
            onChange(currentChars, replacementChars)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading &&
            current.isNotEmpty &&
            replacement.length >= MIN_PASSWORD_LENGTH &&
            replacement.contentEquals(confirmation)
    ) {
        if (isLoading) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Cambiar contraseña")
    }
}

@Composable
private fun RegenerateRecoverySection(
    isLoading: Boolean,
    phrase: List<String>?,
    onRegenerate: (CharArray) -> Unit,
    onAcknowledge: () -> Unit
) {
    val password = remember { SecurePasswordState() }
    var showConfirm by remember { mutableStateOf(false) }

    Text("Frase de recuperación", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Generar una frase nueva invalida la anterior frente a esta bóveda. Se pide la " +
            "contraseña maestra para confirmar que eres tú.",
        style = MaterialTheme.typography.bodySmall
    )

    SecurePasswordField(
        label = "Contraseña maestra",
        state = password,
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth()
    )

    Button(
        onClick = { showConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading && password.isNotEmpty
    ) {
        Text("Regenerar frase de recuperación")
    }

    if (showConfirm) {
        SecureConfirmation(
            title = "¿Regenerar la frase de recuperación?",
            text = "La frase actual dejará de servir de inmediato. Si la pierdes junto con " +
                "la contraseña maestra, la bóveda es irrecuperable: no hay soporte, " +
                "Google ni Firebase que puedan devolverte el acceso.",
            confirmLabel = "Regenerar",
            onConfirm = {
                showConfirm = false
                onRegenerate(password.takeCharsAndClear())
            },
            onDismissRequest = { showConfirm = false }
        )
    }

    if (phrase != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Anótala en papel. No se vuelve a mostrar.", style = MaterialTheme.typography.titleSmall)
                phrase.forEachIndexed { index, word ->
                    Text(
                        text = "${index + 1}. $word",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
                    Text("Ya la anoté, ocultar")
                }
            }
        }
    }
}
