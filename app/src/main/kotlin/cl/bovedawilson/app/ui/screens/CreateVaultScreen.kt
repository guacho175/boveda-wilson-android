package cl.bovedawilson.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.components.SecurePasswordField
import cl.bovedawilson.app.ui.components.SecurePasswordState
import cl.bovedawilson.app.ui.create.CreateVaultViewModel
import cl.bovedawilson.data.sync.session.SessionState

private const val MIN_PASSWORD_LENGTH = 12

/**
 * Creación de la bóveda. Es la primera pantalla en una instalación nueva.
 *
 * Dice sin rodeos que perder la contraseña **y** la frase deja los datos irrecuperables, y
 * no insinúa que exista soporte, Google, Firebase o el desarrollador capaz de recuperarlos
 * según el contrato de seguridad del proyecto.
 */
@Composable
fun CreateVaultScreen(navController: NavHostController, viewModel: CreateVaultViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onAppBackgrounded()
    }

    LaunchedEffect(uiState.finished) {
        if (uiState.finished) {
            navController.navigate(ROUTE_ITEMS) {
                popUpTo(ROUTE_CREATE_VAULT) { inclusive = true }
            }
        }
    }

    LaunchedEffect(uiState.cloudLinkRequired) {
        if (uiState.cloudLinkRequired) {
            navController.navigate(ROUTE_CLOUD_ACCESS) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Si la app se bloquea mientras muestra la frase, se descarta esa copia y se exige
    // desbloquear otra vez. La frase no se vuelve a mostrar: puede regenerarse después
    // desde Configuración con reautenticación.
    LaunchedEffect(sessionState, uiState.vaultCreated) {
        if (uiState.vaultCreated && !uiState.cloudLinkRequired && sessionState is SessionState.Locked) {
            viewModel.onSessionLocked()
            navController.navigate(ROUTE_UNLOCK) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val phrase = uiState.recoveryPhrase
        if (phrase == null) {
            CreateVaultForm(
                isLoading = uiState.isLoading,
                errorMessage = uiState.errorMessage,
                onCreate = viewModel::createVault
            )
        } else if (uiState.isVerifyingRecovery) {
            RecoveryVerificationPanel(
                challengeIndices = uiState.challengeIndices,
                errorMessage = uiState.errorMessage,
                isLoading = uiState.isLoading,
                onVerify = viewModel::verifyRecoveryWords
            )
        } else {
            RecoveryPhrasePanel(
                words = phrase,
                onReadyToVerify = viewModel::startRecoveryVerification
            )
        }
    }
}

@Composable
private fun CreateVaultForm(
    isLoading: Boolean,
    errorMessage: String?,
    onCreate: (CharArray) -> Unit
) {
    val password = remember { SecurePasswordState() }
    val confirmation = remember { SecurePasswordState() }

    Text("Crear tu bóveda", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Elige una contraseña maestra. Se procesa solo en este teléfono: no se envía, " +
            "no se guarda y nadie más puede consultarla.",
        style = MaterialTheme.typography.bodyMedium
    )

    SecurePasswordField(
        label = "Contraseña maestra (mínimo $MIN_PASSWORD_LENGTH caracteres)",
        state = password,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    SecurePasswordField(
        label = "Repite la contraseña",
        state = confirmation,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    if (confirmation.isNotEmpty && !password.contentEquals(confirmation)) {
        Text("Las contraseñas no coinciden.", color = MaterialTheme.colorScheme.error)
    }

    Text(
        "Si pierdes la contraseña maestra y la frase de recuperación, el contenido de la " +
            "bóveda es irrecuperable. No hay forma de restaurarlo.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )

    Button(
        onClick = {
            val submittedPassword = password.takeCharsAndClear()
            confirmation.clear()
            onCreate(submittedPassword)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading &&
            password.length >= MIN_PASSWORD_LENGTH &&
            password.contentEquals(confirmation)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        } else {
            Text("Crear bóveda")
        }
    }

    if (isLoading) {
        Text(
            "Derivando la clave con Argon2id. Puede tardar un par de segundos.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (errorMessage != null) {
        Text(errorMessage, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Muestra la frase **una única vez**. No se puede volver a ver: si se pierde el papel, se
 * regenera desde Configuración (ADR-011).
 */
@Composable
private fun RecoveryPhrasePanel(words: List<String>, onReadyToVerify: () -> Unit) {
    Text("Tu frase de recuperación", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Anota estas 24 palabras en papel, en orden, y guárdalas en un lugar seguro. " +
            "No se muestran otra vez y no quedan almacenadas en ninguna parte.",
        style = MaterialTheme.typography.bodyMedium
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            words.forEachIndexed { index, word ->
                Text(
                    text = "${index + 1}. $word",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    Button(onClick = onReadyToVerify, modifier = Modifier.fillMaxWidth()) {
        Text("Ya la anoté, verificar")
    }
}

@Composable
private fun RecoveryVerificationPanel(
    challengeIndices: List<Int>,
    errorMessage: String?,
    isLoading: Boolean,
    onVerify: (List<String>) -> Unit
) {
    val answers = remember(challengeIndices) {
        mutableStateListOf<String>().apply { repeat(challengeIndices.size) { add("") } }
    }

    fun clearAnswers() {
        for (index in answers.indices) answers[index] = ""
    }

    DisposableEffect(challengeIndices) {
        onDispose(::clearAnswers)
    }

    Text("Verifica tu copia", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Escribe las palabras indicadas. La bóveda no se guardará hasta que coincidan.",
        style = MaterialTheme.typography.bodyMedium
    )

    challengeIndices.forEachIndexed { answerIndex, phraseIndex ->
        OutlinedTextField(
            value = answers[answerIndex],
            onValueChange = { entered ->
                answers[answerIndex] = entered.filterNot(Char::isWhitespace).lowercase().take(32)
            },
            label = { Text("Palabra ${phraseIndex + 1}") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !isLoading
        )
    }

    Button(
        onClick = {
            val submitted = answers.map { it.trim().lowercase() }
            clearAnswers()
            onVerify(submitted)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading && answers.all(String::isNotBlank)
    ) {
        if (isLoading) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Verificar y crear")
    }

    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
