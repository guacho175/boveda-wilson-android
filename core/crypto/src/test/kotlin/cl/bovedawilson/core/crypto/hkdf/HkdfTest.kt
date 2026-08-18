package cl.bovedawilson.core.crypto.hkdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** G-50: los contextos HKDF son únicos y ninguno es prefijo de otro. */
class HkdfTest {

    @Test
    fun `los contextos de HKDF son distintos y ninguno es prefijo del otro`() {
        val a = HkdfContext.PASSWORD_KEK
        val b = HkdfContext.RECOVERY_KEK
        assertFalse(a == b)
        assertFalse(a.startsWith(b))
        assertFalse(b.startsWith(a))
    }

    @Test
    fun `derive produce la longitud solicitada`() {
        val output = Hkdf.derive(ByteArray(32), ByteArray(16), HkdfContext.PASSWORD_KEK, 32)
        assertEquals(32, output.size)
    }

    @Test
    fun `derive con distinto contexto produce salidas distintas para el mismo ikm y salt`() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { it.toByte() }
        val a = Hkdf.derive(ikm, salt, HkdfContext.PASSWORD_KEK)
        val b = Hkdf.derive(ikm, salt, HkdfContext.RECOVERY_KEK)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `derive con distinto salt produce salidas distintas`() {
        val ikm = ByteArray(32) { it.toByte() }
        val a = Hkdf.derive(ikm, ByteArray(16) { 0 }, HkdfContext.PASSWORD_KEK)
        val b = Hkdf.derive(ikm, ByteArray(16) { 1 }, HkdfContext.PASSWORD_KEK)
        assertFalse(a.contentEquals(b))
    }
}
