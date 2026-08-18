package cl.bovedawilson.data.remote.auth

import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.data.remote.firestore.RemoteUnavailableException

/** Variante local-first cuando Firebase no está configurado. */
class OfflineFirebaseAuthSource : FirebaseAuthSource {
    override val isConfigured: Boolean = false
    override val currentUserId: String? = null

    override suspend fun signInWithEmail(email: String, password: CharArray): String = try {
        unavailable()
    } finally {
        Wipe.chars(password)
    }

    override suspend fun signUpWithEmail(email: String, password: CharArray): String = try {
        unavailable()
    } finally {
        Wipe.chars(password)
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): String = unavailable()

    override suspend fun signOut() = Unit

    private fun unavailable(): Nothing = throw RemoteUnavailableException("auth_not_configured")
}
