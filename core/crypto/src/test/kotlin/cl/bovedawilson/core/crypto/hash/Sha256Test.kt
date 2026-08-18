package cl.bovedawilson.core.crypto.hash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Sha256Test {
    @Test
    fun `digest es determinista y la comparacion es constante en la API`() {
        val fixture = "FIXTURE_PUBLIC_NOT_SECRET".encodeToByteArray()
        val first = Sha256.digest(fixture)
        val second = Sha256.digest(fixture)
        val different = Sha256.digest("FIXTURE_OTHER_NOT_SECRET".encodeToByteArray())

        assertTrue(Sha256.equals(first, second))
        assertFalse(Sha256.equals(first, different))
    }
}
