package cl.bovedawilson.core.common.memory

/**
 * Contenedor cerrable para material sensible en memoria (entropía, claves derivadas,
 * serializaciones transitorias de keysets). [close] borra el buffer con [Wipe.bytes],
 * incluso si ya se cerró antes, para que sea seguro llamarlo varias veces o dentro de un
 * `finally` tras una excepción. Toma posesión del array recibido: quien lo construye no
 * debe seguir usando esa referencia por fuera.
 *
 * No declara `data class` ni expone el contenido por `toString()`, para que un `Redact`
 * o un registro accidental nunca impriman el material (`SECURITY.md` §1).
 */
class SecureBytes(bytes: ByteArray) : AutoCloseable {

    private var buffer: ByteArray? = bytes

    val size: Int
        get() = buffer?.size ?: 0

    /**
     * Entrega el array real (no una copia) a [block] mientras este contenedor sigue
     * abierto. Quien reciba el array no debe conservar la referencia más allá de
     * [block]: sigue siendo propiedad de este [SecureBytes] y se borra en [close].
     */
    fun <R> withBytes(block: (ByteArray) -> R): R {
        val current = buffer ?: error("SecureBytes ya fue cerrado")
        return block(current)
    }

    override fun close() {
        buffer?.let(Wipe::bytes)
        buffer = null
    }

    override fun toString(): String = "SecureBytes(${size}B)"
}
