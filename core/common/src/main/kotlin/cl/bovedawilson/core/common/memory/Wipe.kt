package cl.bovedawilson.core.common.memory

/**
 * Borrado en el sitio de buffers sensibles. Mitigación de buena práctica, no garantía
 * absoluta: la JVM no promete que una copia no sobreviva en el recolector de basura o en
 * una página de intercambio (`CRYPTOGRAPHY.md` §13).
 */
object Wipe {
    private const val ZERO_BYTE: Byte = 0
    private const val ZERO_CHAR: Char = ' '

    fun bytes(buffer: ByteArray) {
        buffer.fill(ZERO_BYTE)
    }

    fun chars(buffer: CharArray) {
        buffer.fill(ZERO_CHAR)
    }
}
