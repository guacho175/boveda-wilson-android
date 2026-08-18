package cl.bovedawilson.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Contraparte de release de `src/debug/.../AppCheckInitializer.kt` (mismo nombre,
 * ADR-037). Release instala Play Integrity, pero la consola solo debe activar enforcement
 * después de registrar la huella SHA-256 y comprobar tokens del APK release firmado.
 */
internal object AppCheckInitializer {
    fun installIfConfigured() {
        if (!BuildConfig.HAS_GOOGLE_SERVICES) return
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    }
}
