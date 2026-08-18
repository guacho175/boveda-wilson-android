package cl.bovedawilson.core.crypto.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #14: el buffer de entropía que posee [RecoveryEntropy] queda en cero tras cerrarse. */
class RecoveryEntropyTest {

    @Test
    fun `close deja la entropia en cero`() {
        val entropy = RecoveryEntropy.generate()
        var captured: ByteArray? = null
        entropy.withBytes { captured = it }
        entropy.close()
        assertTrue(captured!!.all { it == 0.toByte() })
    }

    @Test
    fun `toPhrase entrega 24 palabras y no impide cerrar despues`() {
        val entropy = RecoveryEntropy.generate()
        val phrase = entropy.toPhrase()
        assertEquals(24, phrase.wordCount)
        entropy.close()
    }

    @Test
    fun `generate produce 32 bytes de entropia`() {
        val entropy = RecoveryEntropy.generate()
        entropy.withBytes { assertEquals(32, it.size) }
        entropy.close()
    }
}
