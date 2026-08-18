package cl.bovedawilson.core.crypto.hash

import java.security.MessageDigest

/** SHA-256 para ligar datos públicos a una capacidad; no se usa como cifrado ni como KDF. */
object Sha256 {
    fun digest(input: ByteArray): ByteArray = MessageDigest.getInstance(ALGORITHM).digest(input)

    fun equals(left: ByteArray, right: ByteArray): Boolean = MessageDigest.isEqual(left, right)

    private const val ALGORITHM = "SHA-256"
}
