package cl.bovedawilson.app

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Solo existe en el build de depuración: `firebase-appcheck-debug` es
 * `debugImplementation` (`app/build.gradle.kts`) y no está en el classpath de release.
 * La contraparte de `src/release` con el mismo nombre no hace nada (ADR-037).
 */
internal object AppCheckInitializer {
    fun installIfConfigured() {
        // Sin google-services.json (B-01) no hay FirebaseApp por defecto y
        // FirebaseAppCheck.getInstance() lanzaría IllegalStateException.
        if (!BuildConfig.HAS_GOOGLE_SERVICES) return
        FirebaseAppCheck.getInstance()
            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
