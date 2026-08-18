package cl.bovedawilson.data.remote.auth

import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.data.remote.internal.EmulatorConfig
import cl.bovedawilson.data.remote.internal.awaitResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class FirebaseAuthSourceImpl(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    // Solo para pruebas instrumentadas contra un dispositivo físico: "10.0.2.2" es el
    // alias del Android Emulator y no lo resuelve un dispositivo real. Ese caso usa
    // "localhost" con `adb reverse` (ADR-037); el valor por defecto de producción no
    // cambia.
    emulatorHost: String = EmulatorConfig.DEFAULT_HOST
) : FirebaseAuthSource {

    override val isConfigured: Boolean = true

    init {
        EmulatorConfig.configureAuthIfDebug(auth, emulatorHost)
    }

    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override suspend fun signInWithEmail(email: String, password: CharArray): String = try {
        val result = auth.signInWithEmailAndPassword(email, String(password)).awaitResult()
        result.user?.uid ?: error("Firebase Authentication no devolvió un usuario")
    } finally {
        Wipe.chars(password)
    }

    override suspend fun signUpWithEmail(email: String, password: CharArray): String = try {
        val result = auth.createUserWithEmailAndPassword(email, String(password)).awaitResult()
        result.user?.uid ?: error("Firebase Authentication no devolvió un usuario")
    } finally {
        Wipe.chars(password)
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): String {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).awaitResult()
        return result.user?.uid ?: error("Firebase Authentication no devolvió un usuario")
    }

    override suspend fun signOut() {
        auth.signOut()
    }
}
