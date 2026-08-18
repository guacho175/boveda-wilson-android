package cl.bovedawilson.core.crypto.recovery

import cl.bovedawilson.core.common.memory.SecureBytes
import cl.bovedawilson.core.common.memory.Wipe
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/**
 * Entropía de recuperación de 256 bits (`CRYPTOGRAPHY.md` §11), envuelta en [SecureBytes]: no
 * se persiste nunca sin envolver como VDEK y se borra al cerrar, incluso ante excepción.
 * Interna: solo las operaciones de alto nivel de `:core:crypto` la fabrican y la consumen.
 */
internal class RecoveryEntropy private constructor(private val bytes: SecureBytes) : AutoCloseable {

    /** Entrega la entropía cruda a [block] mientras este contenedor sigue abierto. */
    fun <R> withBytes(block: (ByteArray) -> R): R = bytes.withBytes(block)

    /** Codifica esta entropía en sus 24 palabras, para mostrarlas una sola vez. */
    fun toPhrase(): RecoveryPhrase = bytes.withBytes { raw ->
        val chars = Bip39Codec.encode(raw)
        try {
            RecoveryPhrase(splitWords(chars))
        } finally {
            Wipe.chars(chars)
        }
    }

    override fun close() = bytes.close()

    companion object {
        private val secureRandom = SecureRandom()

        fun generate(): RecoveryEntropy {
            val raw = ByteArray(Bip39Codec.ENTROPY_BYTES)
            secureRandom.nextBytes(raw)
            return RecoveryEntropy(SecureBytes(raw))
        }

        /**
         * Reconstruye la entropía desde [words] introducidas por el usuario, normalizadas en el
         * orden exacto de ADR-022 punto 2: NFKD → recorte de extremos → colapso de espacios
         * internos a un único `U+0020` → minúsculas con `Locale.ROOT`. Lanza
         * [cl.bovedawilson.core.crypto.error.CryptoError.InvalidCredentials] si alguna palabra o
         * el checksum no son válidos.
         */
        fun fromWords(words: List<String>): RecoveryEntropy {
            val normalized = words.map(::normalize)
            return RecoveryEntropy(SecureBytes(Bip39Codec.decode(normalized)))
        }

        private fun normalize(word: String): String {
            val nfkd = Normalizer.normalize(word, Normalizer.Form.NFKD)
            return nfkd.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        }

        /**
         * Corta [chars] en palabras sin construir nunca un `String` con la frase completa
         * concatenada (`SECURITY.md` §1: la frase «no se concatena en un
         * `String` persistente»); cada palabra se copia directamente desde el `CharArray`
         * de origen con `String(chars, offset, length)`, sin pasar por un intermedio con
         * las 24 palabras juntas.
         */
        private fun splitWords(chars: CharArray): List<String> {
            val words = mutableListOf<String>()
            var start = 0
            for (i in chars.indices) {
                if (chars[i] == ' ') {
                    if (i > start) words.add(String(chars, start, i - start))
                    start = i + 1
                }
            }
            if (start < chars.size) words.add(String(chars, start, chars.size - start))
            return words
        }
    }
}
