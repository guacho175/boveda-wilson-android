package cl.bovedawilson.core.common.result

/**
 * Conjunto cerrado de errores orientados a la interfaz. `:core:crypto` no produce
 * [AppError] directamente: expone [cl.bovedawilson.core.crypto.error.CryptoError], y
 * `:data:sync` lo traduce a este tipo a partir de la Fase 5 (`docs/architecture.md` §2).
 * Ningún caso lleva material sensible: son categorías, no mensajes con contenido.
 */
sealed class AppError {
    data object InvalidCredentials : AppError()
    data object IntegrityFailure : AppError()
    data object UnsupportedVersion : AppError()
    data object WeakParameters : AppError()
    data object MalformedInput : AppError()
    data object RemoteConflict : AppError()
    data object OperationFailed : AppError()
}
