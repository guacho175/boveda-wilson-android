package cl.bovedawilson.core.common.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class WipeTest {

    @Test
    fun `bytes deja el arreglo en cero`() {
        val buffer = ByteArray(16) { (it + 1).toByte() }
        Wipe.bytes(buffer)
        assertTrue(buffer.all { it == 0.toByte() })
    }

    @Test
    fun `chars deja el arreglo en espacios`() {
        val buffer = "password-ficticia".toCharArray()
        Wipe.chars(buffer)
        assertTrue(buffer.all { it == ' ' })
    }
}
