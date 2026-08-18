package cl.bovedawilson.core.common.memory

import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Convierte una contraseña maestra en `CharArray` a un `ByteArray` UTF-8, sin pasar por
 * `String` en ningún punto intermedio (`SECURITY.md` §1). El `CharArray` de
 * entrada **no** se borra aquí: quien lo posee decide cuándo liberarlo.
 */
fun CharArray.toUtf8Bytes(): ByteArray {
    val charBuffer = CharBuffer.wrap(this)
    val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
    val bytes = ByteArray(byteBuffer.remaining())
    byteBuffer.get(bytes)
    if (byteBuffer.hasArray()) {
        Wipe.bytes(byteBuffer.array())
    }
    return bytes
}

/** Ejecuta [block] con los bytes UTF-8 de [this] y los borra al salir, incluso ante excepción. */
inline fun <R> CharArray.useAsUtf8Bytes(block: (ByteArray) -> R): R {
    val bytes = toUtf8Bytes()
    try {
        return block(bytes)
    } finally {
        Wipe.bytes(bytes)
    }
}
