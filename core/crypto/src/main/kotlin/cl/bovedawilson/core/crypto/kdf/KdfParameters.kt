package cl.bovedawilson.core.crypto.kdf

/**
 * Parámetros públicos (no secretos) de una derivación de contraseña. Se persisten junto al
 * envoltorio y se incluyen en su AAD como defensa en profundidad (`CRYPTOGRAPHY.md` §5).
 *
 * No es `data class`: el contenido de [salt] no es sensible, pero la igualdad estructural
 * por defecto de un `ByteArray` compara referencias, no contenido, lo que rompería
 * silenciosamente las pruebas que comparan parámetros. Se implementan [equals]/[hashCode]
 * a mano en su lugar.
 */
class KdfParameters(
    val kdfName: String,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputLength: Int,
    val salt: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdfParameters) return false
        return kdfName == other.kdfName &&
            memoryKib == other.memoryKib &&
            iterations == other.iterations &&
            parallelism == other.parallelism &&
            outputLength == other.outputLength &&
            salt.contentEquals(other.salt)
    }

    override fun hashCode(): Int {
        var result = kdfName.hashCode()
        result = 31 * result + memoryKib
        result = 31 * result + iterations
        result = 31 * result + parallelism
        result = 31 * result + outputLength
        result = 31 * result + salt.contentHashCode()
        return result
    }

    override fun toString(): String = "KdfParameters(kdfName=$kdfName, salt=${salt.size}B)"
}
