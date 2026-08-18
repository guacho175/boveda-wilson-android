package cl.bovedawilson.app.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.cloud.CloudAccessViewModel
import cl.bovedawilson.app.ui.cloud.CloudAuthMode
import cl.bovedawilson.app.ui.cloud.CloudDestination
import cl.bovedawilson.app.ui.components.SecurePasswordField
import cl.bovedawilson.app.ui.components.SecurePasswordState

private const val MIN_FIREBASE_PASSWORD_LENGTH = 6

@Composable
fun CloudAccessScreen(
    navController: NavHostController,
    viewModel: CloudAccessViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = requireNotNull(LocalActivity.current)

    LaunchedEffect(Unit) {
        viewModel.destinations.collect { destination ->
            val route = when (destination) {
                CloudDestination.CreateVault -> ROUTE_CREATE_VAULT
                CloudDestination.UnlockVault -> ROUTE_UNLOCK
                CloudDestination.Items -> ROUTE_ITEMS
            }
            navController.navigate(route) { popUpTo(ROUTE_CLOUD_ACCESS) { inclusive = true } }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bóveda Wilson", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Firebase solo identifica tu cuenta y sincroniza datos cifrados. Nunca recibe " +
                "tu contraseña maestra, tu frase ni el contenido de tus notas.",
            style = MaterialTheme.typography.bodyMedium
        )

        when {
            uiState.isLoading -> CircularProgressIndicator()
            uiState.showAuthentication -> AuthenticationPanel(
                mode = uiState.authMode,
                deletionPending = uiState.deletionPending,
                errorMessage = uiState.errorMessage,
                onModeChange = viewModel::setAuthMode,
                onGoogleSignIn = { viewModel.signInWithGoogle(activity) },
                onSignIn = viewModel::signIn,
                onSignUp = viewModel::signUp
            )
            uiState.localLinkRequired -> LocalLinkPanel(
                errorMessage = uiState.errorMessage,
                onLink = viewModel::linkLocalVault,
                onSignOut = { viewModel.signOut(activity) }
            )
            uiState.ownerConflict -> OwnerConflictPanel(onSignOut = { viewModel.signOut(activity) })
            uiState.remoteOptions.isNotEmpty() -> RemoteSelectionPanel(
                options = uiState.remoteOptions,
                onSelect = viewModel::selectRemoteVault,
                onSignOut = { viewModel.signOut(activity) }
            )
            uiState.errorMessage != null -> Text(
                uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun AuthenticationPanel(
    mode: CloudAuthMode,
    deletionPending: Boolean,
    errorMessage: String?,
    onModeChange: (CloudAuthMode) -> Unit,
    onGoogleSignIn: () -> Unit,
    onSignIn: (String, CharArray) -> Unit,
    onSignUp: (String, CharArray) -> Unit
) {
    var email by remember { mutableStateOf("") }
    val password = remember { SecurePasswordState() }
    val effectiveMode = if (deletionPending) CloudAuthMode.SignIn else mode

    Button(onClick = onGoogleSignIn, modifier = Modifier.fillMaxWidth()) {
        Text("Continuar con Google")
    }
    Text("También puedes usar correo y contraseña.", style = MaterialTheme.typography.bodySmall)

    if (deletionPending) {
        Text(
            "Hay una eliminacion pendiente. Accede a la misma cuenta que era propietaria " +
                "para completar la purga remota antes de borrar la copia local.",
            color = MaterialTheme.colorScheme.error
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == CloudAuthMode.SignIn,
                onClick = { onModeChange(CloudAuthMode.SignIn) },
                label = { Text("Acceder") }
            )
            FilterChip(
                selected = mode == CloudAuthMode.SignUp,
                onClick = { onModeChange(CloudAuthMode.SignUp) },
                label = { Text("Crear cuenta") }
            )
        }
    }

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Correo de Firebase") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth()
    )
    SecurePasswordField(
        label = "Contraseña de Firebase",
        state = password,
        enabled = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Esta contraseña es distinta de la contraseña maestra de la bóveda.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(
        onClick = {
            val chars = password.takeCharsAndClear()
            if (effectiveMode == CloudAuthMode.SignIn) onSignIn(email, chars) else onSignUp(email, chars)
        },
        enabled = email.isNotBlank() &&
            password.isNotEmpty &&
            (effectiveMode == CloudAuthMode.SignIn || password.length >= MIN_FIREBASE_PASSWORD_LENGTH),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (effectiveMode == CloudAuthMode.SignIn) "Acceder" else "Crear cuenta")
    }
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LocalLinkPanel(
    errorMessage: String?,
    onLink: (CharArray) -> Unit,
    onSignOut: () -> Unit
) {
    val masterPassword = remember { SecurePasswordState() }
    Text("Vincular la bóveda local", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Ya existe una bóveda solo en este dispositivo. Desbloquéala para confirmar que " +
            "quieres vincular sus datos cifrados a esta cuenta.",
        style = MaterialTheme.typography.bodyMedium
    )
    SecurePasswordField(
        label = "Contraseña maestra",
        state = masterPassword,
        enabled = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { onLink(masterPassword.takeCharsAndClear()) },
        enabled = masterPassword.isNotEmpty,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Desbloquear y vincular") }
    Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun OwnerConflictPanel(onSignOut: () -> Unit) {
    Text("Bóveda local protegida", style = MaterialTheme.typography.headlineSmall)
    Text(
        "La bóveda de este dispositivo pertenece a otra cuenta. Permanece bloqueada y " +
            "no se mezclará con la cuenta actual.",
        color = MaterialTheme.colorScheme.error
    )
    Text(
        "Cerrar sesión conserva todos los datos cifrados locales. El descarte local seguro " +
            "requiere una confirmación separada.",
        style = MaterialTheme.typography.bodySmall
    )
    Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
}

@Composable
private fun RemoteSelectionPanel(
    options: List<cl.bovedawilson.data.sync.repo.RemoteVaultOption>,
    onSelect: (String) -> Unit,
    onSignOut: () -> Unit
) {
    Text("Elegir bóveda cifrada", style = MaterialTheme.typography.headlineSmall)
    Text("Hay varias bóvedas. Ninguna se elige automáticamente.")
    options.forEach { option ->
        Button(onClick = { onSelect(option.id) }, modifier = Modifier.fillMaxWidth()) {
            Text("Bóveda ${option.id.take(8)}…")
        }
    }
    Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
}
