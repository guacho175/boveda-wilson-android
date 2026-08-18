package cl.bovedawilson.core.crypto.kdf

import cl.bovedawilson.core.crypto.error.CryptoError
import java.security.SecureRandom

/**
 * Perfil `v1` cerrado del KDF (`CRYPTOGRAPHY.md` §5, ADR-021/ADR-031). Un único perfil
 * publicado; cualquier valor distinto se rechaza **antes** de reservar memoria de Argon2id.
 * Se invoca tanto al derivar como al leer parámetros recibidos de fuera (servidor, respaldo).
 */
object KdfPolicy {
    const val KDF_NAME = "argon2id"
    const val MEMORY_KIB = 65536
    const val ITERATIONS = 3
    const val PARALLELISM = 4
    const val OUTPUT_LENGTH = 32
    const val SALT_LENGTH = 16

    private val secureRandom = SecureRandom()

    /**
     * Valida [params] contra el perfil v1. Primero los campos estructurales (nombre,
     * longitud de salida, longitud de salt) porque una entrada malformada ni siquiera
     * describe un perfil evaluable; después el coste (memoria/iteraciones/paralelismo),
     * que si difiere es un intento de downgrade o de sobrecarga, no un formato inválido.
     */
    fun verify(params: KdfParameters) {
        require(params.kdfName == KDF_NAME, CryptoError.MalformedInput)
        require(params.outputLength == OUTPUT_LENGTH, CryptoError.MalformedInput)
        require(params.salt.size == SALT_LENGTH, CryptoError.MalformedInput)
        val costMatches = params.memoryKib == MEMORY_KIB &&
            params.iterations == ITERATIONS &&
            params.parallelism == PARALLELISM
        require(costMatches, CryptoError.WeakParameters)
    }

    private fun require(condition: Boolean, error: CryptoError) {
        if (!condition) throw error
    }

    /**
     * Parámetros de producción con un `passwordSalt` nuevo. Se usan al **crear** cualquier
     * envoltorio (creación, cambio de contraseña, recuperación, restauración); nunca los
     * que vengan del servidor (ADR-021).
     */
    fun newProductionParameters(): KdfParameters {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)
        return KdfParameters(KDF_NAME, MEMORY_KIB, ITERATIONS, PARALLELISM, OUTPUT_LENGTH, salt)
    }
}
