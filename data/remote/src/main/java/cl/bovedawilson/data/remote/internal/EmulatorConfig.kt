package cl.bovedawilson.data.remote.internal

import cl.bovedawilson.data.remote.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Conecta Auth y Firestore al Firebase Emulator Suite (`firebase/firebase.json`) solo
 * cuando `BuildConfig.DEBUG` es verdadero (ADR-037). En un build de release esta rama
 * nunca se ejecuta: no hay ruta que apunte al emulador fuera de depuración.
 *
 * El host `10.0.2.2` es el alias de loopback del Android Emulator hacia la máquina
 * anfitriona y coincide con la única excepción de tráfico en claro del NSC de debug
 * (`app/src/debug/res/xml/network_security_config.xml`). Un dispositivo físico no
 * resuelve `10.0.2.2`; para probar desde el dispositivo conectado por USB hace falta
 * `adb reverse tcp:9099 tcp:9099` y `adb reverse tcp:8080 tcp:8080` más el host
 * `"localhost"` (ya permitido por el mismo NSC), pasado explícitamente por quien
 * construye la instancia — el valor por defecto de esta clase no cambia para no alterar
 * el comportamiento documentado en `FIREBASE_SETUP.md` sin acordarlo antes.
 */
internal object EmulatorConfig {
    const val DEFAULT_HOST = "10.0.2.2"
    private const val AUTH_EMULATOR_PORT = 9099
    private const val FIRESTORE_EMULATOR_PORT = 8080

    @Volatile
    private var authConfigured = false

    @Volatile
    private var firestoreConfigured = false

    @Synchronized
    fun configureAuthIfDebug(auth: FirebaseAuth, host: String = DEFAULT_HOST) {
        if (!BuildConfig.DEBUG || !BuildConfig.USE_FIREBASE_EMULATOR || authConfigured) return
        auth.useEmulator(host, AUTH_EMULATOR_PORT)
        authConfigured = true
    }

    @Synchronized
    fun configureFirestoreIfDebug(firestore: FirebaseFirestore, host: String = DEFAULT_HOST) {
        if (!BuildConfig.DEBUG || !BuildConfig.USE_FIREBASE_EMULATOR || firestoreConfigured) return
        firestore.useEmulator(host, FIRESTORE_EMULATOR_PORT)
        firestoreConfigured = true
    }
}
