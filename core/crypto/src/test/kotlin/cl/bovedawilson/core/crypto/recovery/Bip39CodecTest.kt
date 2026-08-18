package cl.bovedawilson.core.crypto.recovery

import cl.bovedawilson.core.crypto.error.CryptoError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectores oficiales de BIP-39 (256 bits de entropía, lista inglesa), tomados de
 * `vectors.json` en https://github.com/trezor/python-mnemonic (MIT). No son secretos: son
 * casos de prueba públicos y deterministas del estándar, citados como tales
 * (`docs/TEST_STRATEGY.md` §1).
 */
class Bip39CodecTest {

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        val high = Character.digit(hex[i * 2], 16)
        val low = Character.digit(hex[i * 2 + 1], 16)
        ((high shl 4) + low).toByte()
    }

    @Test
    fun `G-1 el checksum de todo cero entropia codifica en la frase oficial`() {
        val entropy = hexToBytes("0".repeat(64))
        val chars = Bip39Codec.encode(entropy)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
            String(chars),
        )
    }

    @Test
    fun `todo uno entropia codifica en la frase oficial`() {
        val entropy = hexToBytes("f".repeat(64))
        val chars = Bip39Codec.encode(entropy)
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
            String(chars),
        )
    }

    @Test
    fun `vector aleatorio oficial decodifica hacia la entropia original`() {
        val entropy = hexToBytes("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c")
        val expectedWords = (
            "hamster diagram private dutch cause delay private meat slide toddler razor book happy " +
                "fancy gospel tennis maple dilemma loan word shrug inflict delay length"
            ).split(" ")

        val encoded = String(Bip39Codec.encode(entropy)).split(" ")
        assertEquals(expectedWords, encoded)

        val decoded = Bip39Codec.decode(expectedWords)
        assertTrue(entropy.contentEquals(decoded))
    }

    @Test
    fun `decode de la frase oficial de todo cero reconstruye la entropia`() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        val decoded = Bip39Codec.decode(words.split(" "))
        assertTrue(decoded.contentEquals(hexToBytes("0".repeat(64))))
    }

    @Test
    fun `una palabra fuera de la lista falla con InvalidCredentials`() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "noexisteenlalista"
        assertThrows(CryptoError.InvalidCredentials::class.java) { Bip39Codec.decode(words.split(" ")) }
    }

    @Test
    fun `una sola palabra transpuesta rompe el checksum`() {
        // Se intercambian las dos últimas palabras válidas de la frase de todo-cero: mismo
        // conjunto de palabras válidas, checksum roto.
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art abandon"
        val words = phrase.split(" ")
        assertThrows(CryptoError.InvalidCredentials::class.java) { Bip39Codec.decode(words) }
    }

    @Test
    fun `numero de palabras distinto de 24 falla`() {
        assertThrows(CryptoError.InvalidCredentials::class.java) { Bip39Codec.decode(listOf("abandon")) }
    }

    @Test
    fun `ADR-022 punto 5 la lista de palabras es la inglesa oficial de 2048 entradas`() {
        val list = Bip39Codec.wordList()
        assertEquals(2048, list.size)
        assertEquals("abandon", list.first())
        assertEquals("zoo", list.last())
        assertEquals(list.sorted(), list)
    }
}
