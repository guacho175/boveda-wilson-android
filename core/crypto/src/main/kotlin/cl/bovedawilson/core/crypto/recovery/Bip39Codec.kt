package cl.bovedawilson.core.crypto.recovery

import cash.z.ecc.android.bip39.Mnemonics
import cl.bovedawilson.core.crypto.error.CryptoError
import java.security.MessageDigest

/**
 * Codec entropía ↔ 24 palabras BIP-39, con la lista inglesa fija (ADR-008, ADR-022). La
 * decodificación no usa `Mnemonics.MnemonicCode.validate()`/`toEntropy()` de la biblioteca:
 * esas rutas comparan palabra por palabra y bit por bit con retorno anticipado, lo que filtra
 * por temporización en qué palabra o en qué bit del checksum falló una entrada. Aquí la
 * búsqueda en la lista y la comparación del checksum recorren siempre el mismo número de
 * pasos, sin salir antes (ADR-022 punto 4).
 */
internal object Bip39Codec {
    const val LANGUAGE_CODE = "en"
    const val WORD_COUNT = 24
    const val ENTROPY_BYTES = 32
    private const val BITS_PER_WORD = 11
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val TOTAL_BITS = WORD_COUNT * BITS_PER_WORD
    private const val CHECKSUM_BITS = TOTAL_BITS - ENTROPY_BYTES * BITS_PER_BYTE

    /** Lista oficial de 2048 palabras inglesas. Pública dentro del módulo para que una prueba
     * afirme su identidad (ADR-022 punto 5): si la biblioteca cambia de lista, la prueba falla. */
    fun wordList(): List<String> = Mnemonics.getCachedWords(LANGUAGE_CODE)

    /** Codifica [entropy] (32 bytes) en sus 24 palabras. No borra [entropy]: quien llama decide. */
    fun encode(entropy: ByteArray): CharArray {
        if (entropy.size != ENTROPY_BYTES) throw CryptoError.InternalError
        return Mnemonics.MnemonicCode(entropy, LANGUAGE_CODE).chars
    }

    /**
     * Decodifica [words] (ya normalizadas por [RecoveryEntropy]) hacia la entropía original.
     * Una palabra fuera de la lista o un checksum incorrecto producen el mismo
     * [CryptoError.InvalidCredentials], en un tiempo que no depende de dónde ocurrió el fallo.
     */
    fun decode(words: List<String>): ByteArray {
        if (words.size != WORD_COUNT) throw CryptoError.InvalidCredentials
        val list = wordList()

        var anyInvalid = false
        val indices = IntArray(WORD_COUNT)
        for (i in 0 until WORD_COUNT) {
            val index = constantTimeIndexOf(list, words[i])
            if (index < 0) anyInvalid = true
            indices[i] = if (index < 0) 0 else index
        }

        val bits = BooleanArray(TOTAL_BITS)
        for (w in 0 until WORD_COUNT) {
            val value = indices[w]
            for (b in 0 until BITS_PER_WORD) {
                bits[w * BITS_PER_WORD + b] = (value shr (BITS_PER_WORD - 1 - b)) and 1 == 1
            }
        }

        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in entropy.indices) {
            var byteValue = 0
            for (b in 0 until BITS_PER_BYTE) {
                byteValue = (byteValue shl 1) or (if (bits[i * BITS_PER_BYTE + b]) 1 else 0)
            }
            entropy[i] = byteValue.toByte()
        }

        var checksumFromWords = 0
        for (b in 0 until CHECKSUM_BITS) {
            checksumFromWords = (checksumFromWords shl 1) or (if (bits[ENTROPY_BYTES * BITS_PER_BYTE + b]) 1 else 0)
        }

        val expectedByte = sha256(entropy)[0].toInt() and BYTE_MASK
        val expectedTopBits = expectedByte ushr (BITS_PER_BYTE - CHECKSUM_BITS)
        val checksumMatches = (checksumFromWords xor expectedTopBits) == 0

        if (anyInvalid || !checksumMatches) {
            entropy.fill(0)
            throw CryptoError.InvalidCredentials
        }
        return entropy
    }

    /**
     * Longitud máxima de una palabra de la lista, calculada una sola vez sobre la lista
     * pública (no es un dato secreto). Sirve para que [constantTimeEquals] compare siempre
     * el mismo número de caracteres, sin importar la longitud real de ninguno de los dos
     * operandos.
     */
    private val maxWordLength: Int by lazy(LazyThreadSafetyMode.NONE) { wordList().maxOf { it.length } }

    /** Recorre siempre las 2048 palabras, sin retorno anticipado al encontrar la coincidencia. */
    private fun constantTimeIndexOf(list: List<String>, candidate: String): Int {
        val padLength = maxWordLength
        var found = -1
        for (i in list.indices) {
            if (constantTimeEquals(list[i], candidate, padLength)) found = i
        }
        return found
    }

    /**
     * Compara [a] y [b] recorriendo siempre [padLength] posiciones, para que el tiempo no
     * dependa de la longitud de ninguno de los dos operandos: a diferencia de un
     * `if (a.length != b.length) return false` inicial, que haría que la comparación
     * completa solo se ejecutara para las palabras de la lista cuya longitud coincidiera
     * con la de [b] —revelando por temporización la longitud de la palabra candidata—, aquí
     * la discrepancia de longitud se acumula en el mismo `diff` que el resto de bits.
     */
    private fun constantTimeEquals(a: String, b: String, padLength: Int): Boolean {
        var diff = a.length xor b.length
        for (i in 0 until padLength) {
            val charA = if (i < a.length) a[i].code else 0
            val charB = if (i < b.length) b[i].code else 0
            diff = diff or (charA xor charB)
        }
        return diff == 0
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
