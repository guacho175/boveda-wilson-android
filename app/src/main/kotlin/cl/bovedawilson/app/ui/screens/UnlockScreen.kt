package cl.bovedawilson.app.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import cl.bovedawilson.app.ui.biometric.BiometricPromptCopy
import cl.bovedawilson.app.ui.biometric.showBiometricPrompt
import cl.bovedawilson.app.ui.components.SecurePasswordField
import cl.bovedawilson.app.ui.components.SecurePasswordState
import cl.bovedawilson.app.ui.unlock.UnlockViewModel
import kotlinx.coroutines.launch

private const val RECOVERY_WORD_COUNT = 24

@Composable
fun UnlockScreen(navController: NavHostController, viewModel: UnlockViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var showRecovery by remember { mutableStateOf(false) }
    var biometricOffered by remember { mutableStateOf(false) }
    val activity = LocalActivity.current as FragmentActivity
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.unlockSuccess) {
        if (uiState.unlockSuccess) {
            navController.navigate(ROUTE_ITEMS) {
                popUpTo(ROUTE_UNLOCK) { inclusive = true }
            }
        }
    }

    LaunchedEffect(Unit) {
        biometricOffered = viewModel.canOfferBiometricUnlock()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text("Bóveda Wilson", style = MaterialTheme.typography.headlineLarge)

            if (biometricOffered && !showRecovery) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val cryptoObject = viewModel.prepareBiometricUnlockCipher()
                            if (cryptoObject == null) {
                                biometricOffered = false
                                return@launch
                            }
                            showBiometricPrompt(
                                activity = activity,
                                cryptoObject = cryptoObject,
                                copy = BiometricPromptCopy("Desbloquear Bóveda Wilson", "Usar contraseña maestra"),
                                onSuccess = { cipher -> viewModel.unlockWithBiometric(cipher) },
                                onError = { }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Text(" Desbloquear con biometría")
                }
            }

            if (showRecovery) {
                RecoveryUnlockForm(
                    isLoading = uiState.isLoading,
                    onUnlock = viewModel::unlockWithRecovery,
                    onBack = { showRecovery = false }
                )
            } else {
                PasswordUnlockForm(
                    isLoading = uiState.isLoading,
                    onUnlock = viewModel::unlockWithPassword,
                    onUseRecovery = { showRecovery = true }
                )
            }

            if (uiState.errorMessage != null) {
                Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PasswordUnlockForm(
    isLoading: Boolean,
    onUnlock: (CharArray) -> Unit,
    onUseRecovery: () -> Unit
) {
    val password = remember { SecurePasswordState() }

    SecurePasswordField(
        label = "Contraseña maestra",
        state = password,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isLoading
    )

    Button(
        onClick = {
            onUnlock(password.takeCharsAndClear())
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = password.isNotEmpty && !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("Desbloquear")
        }
    }

    TextButton(onClick = onUseRecovery, enabled = !isLoading) {
        Text("Usar frase de recuperación")
    }
}

@Composable
private fun RecoveryUnlockForm(
    isLoading: Boolean,
    onUnlock: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    val words = remember {
        mutableStateListOf<String>().apply {
            repeat(RECOVERY_WORD_COUNT) { add("") }
        }
    }

    fun clearWords() {
        for (index in words.indices) words[index] = ""
    }

    DisposableEffect(Unit) {
        onDispose(::clearWords)
    }

    Text(
        "Escribe las $RECOVERY_WORD_COUNT palabras separadas por espacios, en orden.",
        style = MaterialTheme.typography.bodyMedium
    )

    words.forEachIndexed { index, word ->
        OutlinedTextField(
            value = word,
            onValueChange = { entered ->
                words[index] = entered.filterNot(Char::isWhitespace).lowercase().take(32)
            },
            label = { Text("Palabra ${index + 1}") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !isLoading
        )
    }

    Button(
        onClick = {
            val submittedWords = words.map { it.trim().lowercase() }
            clearWords()
            onUnlock(submittedWords)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = words.all(String::isNotBlank) && !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text("Recuperar acceso")
        }
    }

    TextButton(
        onClick = {
            clearWords()
            onBack()
        },
        enabled = !isLoading
    ) {
        Text("Volver a la contraseña maestra")
    }
}
