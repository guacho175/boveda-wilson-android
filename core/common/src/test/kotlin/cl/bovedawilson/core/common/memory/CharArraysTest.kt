package cl.bovedawilson.core.common.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CharArraysTest {

    @Test
    fun `toUtf8Bytes codifica ASCII correctamente`() {
        val chars = "clave-ficticia".toCharArray()
        val bytes = chars.toUtf8Bytes()
        assertArrayEquals("clave-ficticia".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `toUtf8Bytes codifica caracteres multibyte correctamente`() {
        val chars = "contraseña-ñoño-日本語".toCharArray()
        val bytes = chars.toUtf8Bytes()
        assertArrayEquals("contraseña-ñoño-日本語".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `useAsUtf8Bytes borra los bytes derivados al salir`() {
        val chars = "clave-ficticia".toCharArray()
        var captured: ByteArray? = null
        chars.useAsUtf8Bytes { bytes ->
            captured = bytes
        }
        assertTrue(captured!!.all { it == 0.toByte() })
    }

    @Test
    fun `useAsUtf8Bytes borra los bytes derivados incluso ante excepcion`() {
        val chars = "clave-ficticia".toCharArray()
        var captured: ByteArray? = null
        assertThrows(IllegalStateException::class.java) {
            chars.useAsUtf8Bytes { bytes ->
                captured = bytes
                error("fallo de prueba")
            }
        }
        assertTrue(captured!!.all { it == 0.toByte() })
    }

    @Test
    fun `useAsUtf8Bytes no modifica el CharArray original`() {
        val original = "clave-ficticia".toCharArray()
        val copy = original.copyOf()
        original.useAsUtf8Bytes { }
        assertArrayEquals(copy, original)
    }
}
