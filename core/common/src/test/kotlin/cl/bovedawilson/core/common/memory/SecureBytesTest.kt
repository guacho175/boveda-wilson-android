package cl.bovedawilson.core.common.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBytesTest {

    @Test
    fun `withBytes entrega el contenido original`() {
        val original = byteArrayOf(1, 2, 3, 4)
        SecureBytes(original.copyOf()).use { secure ->
            secure.withBytes { assertEquals(listOf<Byte>(1, 2, 3, 4), it.toList()) }
        }
    }

    @Test
    fun `close borra el buffer`() {
        val original = byteArrayOf(1, 2, 3, 4)
        var captured: ByteArray? = null
        val secure = SecureBytes(original)
        secure.withBytes { captured = it }
        secure.close()
        assertTrue(captured!!.all { it == 0.toByte() })
    }

    @Test
    fun `close es seguro de llamar mas de una vez`() {
        val secure = SecureBytes(byteArrayOf(1, 2, 3))
        secure.close()
        secure.close()
    }

    @Test
    fun `withBytes despues de close lanza excepcion`() {
        val secure = SecureBytes(byteArrayOf(1, 2, 3))
        secure.close()
        assertThrows(IllegalStateException::class.java) { secure.withBytes { } }
    }

    @Test
    fun `toString no revela el contenido`() {
        val secure = SecureBytes(byteArrayOf(1, 2, 3))
        assertEquals("SecureBytes(3B)", secure.toString())
    }
}
