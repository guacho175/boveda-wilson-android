package cl.bovedawilson.core.crypto.error

/**
 * Conjunto cerrado de errores criptográficos (`CRYPTOGRAPHY.md` §13). Ninguno lleva
 * material sensible en el mensaje, en la causa ni en campos adicionales: son categorías
 * hacia quien llama, no diagnósticos con contenido. No cruza a `:app`; `:data:sync` lo
 * traduce a `AppError` (`docs/architecture.md` §2).
 */
sealed class CryptoError(message: String) : Exception(message) {
    /** Contraseña maestra o palabra de recuperación incorrecta. Indistinguible de un
     * ciphertext alterado desde el punto de vista de quien ataca (`CRYPTOGRAPHY.md` §8). */
    data object InvalidCredentials : CryptoError("invalid_credentials")

    /** Autenticación fallida de un ciphertext ya desbloqueada la bóveda: datos alterados. */
    data object IntegrityFailure : CryptoError("integrity_failure")

    /** `cryptoVersion` o `schemaVersion` desconocida. */
    data object UnsupportedVersion : CryptoError("unsupported_version")

    /** Parámetros de KDF fuera del perfil cerrado de su versión. */
    data object WeakParameters : CryptoError("weak_parameters")

    /** Entrada estructuralmente inválida: campo fuera de su juego de caracteres, longitud
     * incorrecta, nombre de algoritmo inesperado. */
    data object MalformedInput : CryptoError("malformed_input")

    /** Fallo interno no atribuible a la entrada del usuario. */
    data object InternalError : CryptoError("internal_error")
}
