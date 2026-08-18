package cl.bovedawilson.core.crypto.version

/**
 * Versión del contrato criptográfico (primitivas, KDF, AAD, wrapping). Una versión
 * desconocida se rechaza siempre; nunca se intenta descifrar «por si acaso»
 * (`CRYPTOGRAPHY.md` §12).
 */
@JvmInline
value class CryptoVersion(val value: Int) {
    companion object {
        val V1 = CryptoVersion(1)
    }
}
