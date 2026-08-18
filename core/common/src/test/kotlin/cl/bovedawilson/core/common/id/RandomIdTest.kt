package cl.bovedawilson.core.common.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomIdTest {

    @Test
    fun `generate produce valores hexadecimales de longitud fija`() {
        val id = RandomId.generate()

        assertEquals(32, id.value.length)
        assertTrue("Debe ser hexadecimal en minúsculas", id.value.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `generate no repite valores entre llamadas`() {
        val primero = RandomId.generate()
        val segundo = RandomId.generate()

        assertNotEquals(primero.value, segundo.value)
    }
}
