package cl.bovedawilson.app.ui.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

private const val CLIPBOARD_LABEL_PREFIX = "Bóveda Wilson"
private const val CLEAR_AFTER_MS = 30_000L

@Volatile
private var currentClipToken: String? = null

/**
 * Copia texto al portapapeles del sistema marcándolo como sensible
 * (`ClipDescription.EXTRA_IS_SENSITIVE`, disponible desde API 33 = `minSdk`): el sistema
 * oculta la vista previa del portapapeles para este contenido. Lo borra automáticamente
 * tras un plazo corto, solo si nadie más volvió a escribir en el portapapeles mientras
 * tanto (`SECURITY.md` §5: "nada se copia automáticamente"; aquí copiar
 * sigue exigiendo una acción explícita del usuario, esta función solo gestiona el borrado).
 */
fun copySensitiveText(context: Context, text: String) {
    val appContext = context.applicationContext
    val clipboardManager = appContext.getSystemService(ClipboardManager::class.java) ?: return
    // El token identifica nuestro clip sin retener una segunda referencia al plaintext
    // durante el plazo de borrado. También evita borrar un clip posterior del usuario.
    val clipToken = "$CLIPBOARD_LABEL_PREFIX:${UUID.randomUUID()}"
    val clip = ClipData.newPlainText(clipToken, text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboardManager.setPrimaryClip(clip)
    currentClipToken = clipToken

    // No usa el scope de la composición: ese scope se cancelaría justo al bloquear la
    // bóveda y dejaría el contenido en el portapapeles. El Handler de proceso conserva
    // solo el token no sensible, nunca `text`.
    Handler(Looper.getMainLooper()).postDelayed(
        {
            val stillOurs = clipboardManager.primaryClipDescription?.label?.toString() == clipToken
            if (stillOurs) {
                clipboardManager.clearPrimaryClip()
            }
            if (currentClipToken == clipToken) currentClipToken = null
        },
        CLEAR_AFTER_MS
    )
}

/** Borra inmediatamente el clip propio al cerrar la sesión, sin tocar uno posterior. */
fun clearSensitiveClipboard(context: Context) {
    val token = currentClipToken ?: return
    val manager = context.applicationContext.getSystemService(ClipboardManager::class.java) ?: return
    if (manager.primaryClipDescription?.label?.toString() == token) manager.clearPrimaryClip()
    currentClipToken = null
}
