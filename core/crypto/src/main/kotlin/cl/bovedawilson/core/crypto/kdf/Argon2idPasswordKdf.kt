package cl.bovedawilson.core.crypto.kdf

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id de producción mediante BouncyCastle (`Argon2BytesGenerator`), JVM pura
 * (ADR-006). [KdfPolicy.verify] se invoca **antes** de construir los parámetros de
 * Argon2, para no reservar memoria con un perfil fuera del cerrado.
 */
class Argon2idPasswordKdf : PasswordKdf {
    override fun derive(password: ByteArray, params: KdfParameters): ByteArray {
        KdfPolicy.verify(params)
        val argon2Parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(params.salt)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(argon2Parameters)
        val output = ByteArray(params.outputLength)
        generator.generateBytes(password, output)
        return output
    }
}
