package cl.bovedawilson.core.crypto.kdf

import cl.bovedawilson.core.crypto.error.CryptoError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class Argon2idPasswordKdfTest {

    @Test
    fun `deriva 32 bytes deterministas para la misma entrada`() {
        val kdf = Argon2idPasswordKdf()
        val params = KdfParameters("argon2id", 65536, 3, 4, 32, ByteArray(16) { it.toByte() })
        val a = kdf.derive("password-ficticia".toByteArray(), params)
        val b = kdf.derive("password-ficticia".toByteArray(), params)
        assertEquals(32, a.size)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `distinto salt produce salida distinta`() {
        val kdf = Argon2idPasswordKdf()
        val paramsA = KdfParameters("argon2id", 65536, 3, 4, 32, ByteArray(16) { 0 })
        val paramsB = KdfParameters("argon2id", 65536, 3, 4, 32, ByteArray(16) { 1 })
        val a = kdf.derive("password-ficticia".toByteArray(), paramsA)
        val b = kdf.derive("password-ficticia".toByteArray(), paramsB)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `distinta contrasena produce salida distinta`() {
        val kdf = Argon2idPasswordKdf()
        val salt = ByteArray(16) { it.toByte() }
        val a = kdf.derive("password-uno".toByteArray(), KdfParameters("argon2id", 65536, 3, 4, 32, salt))
        val b = kdf.derive("password-dos".toByteArray(), KdfParameters("argon2id", 65536, 3, 4, 32, salt))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `un perfil fuera de rango se rechaza antes de reservar memoria`() {
        val kdf = Argon2idPasswordKdf()
        // Memoria absurda: si el rechazo no ocurriera antes de reservar, esta prueba colgaría
        // el proceso o agotaría la memoria en vez de fallar de inmediato.
        val hostil = KdfParameters("argon2id", 1_073_741_824, 3, 4, 32, ByteArray(16))
        assertThrows(CryptoError.WeakParameters::class.java) { kdf.derive("x".toByteArray(), hostil) }
    }
}
