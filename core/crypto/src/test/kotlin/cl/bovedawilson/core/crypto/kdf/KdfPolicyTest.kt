package cl.bovedawilson.core.crypto.kdf

import cl.bovedawilson.core.crypto.error.CryptoError
import org.junit.Assert.assertThrows
import org.junit.Test

/** G-49/G-53/#9: el perfil v1 cerrado se rechaza campo por campo, antes de derivar. */
class KdfPolicyTest {

    private val base = KdfParameters("argon2id", 65536, 3, 4, 32, ByteArray(16))

    private fun withKdfName(kdfName: String) =
        KdfParameters(kdfName, base.memoryKib, base.iterations, base.parallelism, base.outputLength, base.salt)

    private fun withMemoryKib(memoryKib: Int) =
        KdfParameters(base.kdfName, memoryKib, base.iterations, base.parallelism, base.outputLength, base.salt)

    private fun withIterations(iterations: Int) =
        KdfParameters(base.kdfName, base.memoryKib, iterations, base.parallelism, base.outputLength, base.salt)

    private fun withParallelism(parallelism: Int) =
        KdfParameters(base.kdfName, base.memoryKib, base.iterations, parallelism, base.outputLength, base.salt)

    private fun withOutputLength(outputLength: Int) =
        KdfParameters(base.kdfName, base.memoryKib, base.iterations, base.parallelism, outputLength, base.salt)

    private fun withSalt(salt: ByteArray) =
        KdfParameters(base.kdfName, base.memoryKib, base.iterations, base.parallelism, base.outputLength, salt)

    @Test
    fun `el perfil de produccion pasa la verificacion`() {
        KdfPolicy.verify(KdfPolicy.newProductionParameters())
    }

    @Test
    fun `kdfName argon2i se rechaza`() {
        assertThrows(CryptoError.MalformedInput::class.java) { KdfPolicy.verify(withKdfName("argon2i")) }
    }

    @Test
    fun `kdfName argon2d se rechaza`() {
        assertThrows(CryptoError.MalformedInput::class.java) { KdfPolicy.verify(withKdfName("argon2d")) }
    }

    @Test
    fun `kdfName inesperado se rechaza`() {
        assertThrows(CryptoError.MalformedInput::class.java) { KdfPolicy.verify(withKdfName("pbkdf2")) }
    }

    @Test
    fun `memoria distinta de 65536 se rechaza como parametro debil`() {
        assertThrows(CryptoError.WeakParameters::class.java) { KdfPolicy.verify(withMemoryKib(1024)) }
    }

    @Test
    fun `memoria por encima del perfil tambien se rechaza`() {
        assertThrows(CryptoError.WeakParameters::class.java) { KdfPolicy.verify(withMemoryKib(1_048_576)) }
    }

    @Test
    fun `iteraciones distintas de 3 se rechazan`() {
        assertThrows(CryptoError.WeakParameters::class.java) { KdfPolicy.verify(withIterations(1)) }
    }

    @Test
    fun `paralelismo distinto de 4 se rechaza`() {
        assertThrows(CryptoError.WeakParameters::class.java) { KdfPolicy.verify(withParallelism(1)) }
    }

    @Test
    fun `longitud de salida distinta de 32 se rechaza como entrada malformada`() {
        assertThrows(CryptoError.MalformedInput::class.java) { KdfPolicy.verify(withOutputLength(16)) }
    }

    @Test
    fun `salt distinto de 16 bytes se rechaza como entrada malformada`() {
        assertThrows(CryptoError.MalformedInput::class.java) { KdfPolicy.verify(withSalt(ByteArray(8))) }
    }
}
