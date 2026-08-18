package cl.bovedawilson.core.crypto.hkdf

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/** Fachada de HKDF-SHA-256 (extract-then-expand). Interna: solo `:core:crypto` deriva
 * subclaves; nadie fuera del módulo debe poder invocar HKDF con un contexto arbitrario. */
internal object Hkdf {
    private const val DEFAULT_LENGTH = 32

    fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int = DEFAULT_LENGTH): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.UTF_8)))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }
}
