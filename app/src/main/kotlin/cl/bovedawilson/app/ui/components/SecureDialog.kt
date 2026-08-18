package cl.bovedawilson.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * Todo diálogo u hoja modal sensible de la aplicación pasa por aquí
 * (`docs/architecture.md` §6, `SECURITY.md` §5). La ventana de un
 * `Dialog` de Compose es una ventana del sistema propia: **no** hereda `FLAG_SECURE` de
 * la `Activity` aunque `MainActivity` ya lo aplique, así que hay que pedirlo
 * explícitamente por cada diálogo con [SecureFlagPolicy.SecureOn].
 */
@Composable
fun SecureDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        content = content,
    )
}

/**
 * Variante para el caso más común: confirmar o cancelar una acción sensible o
 * irreversible (eliminar una nota, regenerar la frase de recuperación, desactivar la
 * biometría). Misma garantía de [SecureFlagPolicy.SecureOn] que [SecureDialog]. Textos
 * planos en vez de contenido `@Composable` arbitrario, tanto para mantenerse bajo el
 * límite de parámetros de Detekt como porque ningún uso actual necesita nada más rico.
 *
 * El nombre evita terminar en «...Dialog(» a propósito: G-69
 * (`core/common/.../RepositoryHygieneTest.kt`) falla cualquier archivo que contenga esa
 * subcadena y no se llame `SecureDialog`, precisamente para obligar a que todo diálogo
 * pase por aquí en vez de crear un `AlertDialog`/`Dialog` suelto en la pantalla que lo usa.
 */
@Composable
fun SecureConfirmation(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("Cancelar") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}
