package cl.bovedawilson.core.crypto.hkdf

/**
 * Cadenas de contexto de HKDF-SHA-256, congeladas por versión (`CRYPTOGRAPHY.md` §6).
 * Cualquier cambio invalida el material derivado; por eso son constantes, nunca
 * construidas en tiempo de ejecución, y ninguna es prefijo de otra.
 */
object HkdfContext {
    const val PASSWORD_KEK = "BovedaWilson/v1/password-kek"
    const val RECOVERY_KEK = "BovedaWilson/v1/recovery-kek"
}
