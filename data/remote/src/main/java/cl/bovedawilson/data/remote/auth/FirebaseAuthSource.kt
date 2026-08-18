package cl.bovedawilson.data.remote.auth

/**
 * Identifica y autoriza al usuario ante Firebase. Nunca descifra ni conoce material
 * criptográfico de la bóveda: solo entrega un `uid` que
 * `FirestoreVaultSource` usa para anclar la ruta `users/{uid}/...`.
 */
interface FirebaseAuthSource {
    val isConfigured: Boolean
    val currentUserId: String?

    /** Consume [password] y garantiza su borrado incluso si Firebase falla o se cancela. */
    suspend fun signInWithEmail(email: String, password: CharArray): String

    /** Consume [password] y garantiza su borrado incluso si Firebase falla o se cancela. */
    suspend fun signUpWithEmail(email: String, password: CharArray): String

    /**
     * Intercambia un ID token de Google (obtenido por la UI con Credential Manager /
     * Google Sign-In, fuera de este módulo) por una sesión de Firebase Auth. Este
     * módulo nunca lanza el flujo de Google Sign-In: eso exige un `Activity` y
     * corresponde a `:app` en la Fase 7.
     */
    suspend fun signInWithGoogleIdToken(idToken: String): String

    suspend fun signOut()
}
