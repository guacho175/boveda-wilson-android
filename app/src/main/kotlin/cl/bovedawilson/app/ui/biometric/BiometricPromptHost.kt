package cl.bovedawilson.app.ui.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Lanza el diálogo del sistema de `BiometricPrompt` sobre el `CryptoObject` ya preparado
 * por `:data:sync` (`VaultRepository.prepareBiometric*Cipher`). Vive en `:app` porque
 * `BiometricPrompt` exige un `FragmentActivity` como huésped (ADR-019 punto 5): es la
 * única pieza que toca la ventana del sistema. El `Cipher` ya autenticado vuelve intacto a
 * la capa de datos, que completa la operación criptográfica; esta función nunca ve la VDEK
 * ni la `BiometricKEK`.
 */
fun showBiometricPrompt(
    activity: FragmentActivity,
    cryptoObject: BiometricPrompt.CryptoObject,
    copy: BiometricPromptCopy,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val cipher = result.cryptoObject?.cipher
            if (cipher != null) onSuccess(cipher) else onError("No se pudo completar la autenticación.")
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError(errString.toString())
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(copy.title)
        .setNegativeButtonText(copy.negativeButtonText)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(promptInfo, cryptoObject)
}
