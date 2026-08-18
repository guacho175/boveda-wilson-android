package cl.bovedawilson.core.crypto.wrap

import cl.bovedawilson.core.crypto.aead.Aad
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.keys.KekImporter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Envuelve y desenvuelve la VDEK con el formato de keyset cifrado de Tink
 * (`CRYPTOGRAPHY.md` §8): no hay mecanismo propio de wrapping. Sin la KEK correcta o con
 * una AAD distinta, el desenvolvido falla por autenticación — no hay «descifrado
 * parcial» — y ambos casos se traducen a [CryptoError.InvalidCredentials]: una contraseña
 * o palabra incorrecta es indistinguible de un envoltorio alterado desde fuera.
 */
internal object VdekWrapper {
    /**
     * La causa original no se encadena: podría llevar bytes de la VDEK o de la KEK, y
     * `CRYPTOGRAPHY.md` §13 exige que las excepciones criptográficas no lleven material
     * sensible, ni en el mensaje ni en la causa.
     */
    @Suppress("SwallowedException")
    fun wrap(vdek: KeysetHandle, kekBytes: ByteArray, aad: Aad): WrappedVdek {
        val kekAead = KekImporter.importKek(kekBytes)
        val serialized = try {
            TinkProtoKeysetFormat.serializeEncryptedKeyset(vdek, kekAead, aad.bytes)
        } catch (e: GeneralSecurityException) {
            throw CryptoError.InternalError
        }
        return WrappedVdek(serialized)
    }

    /** Ver la nota de [wrap] sobre por qué no se encadena la causa original. */
    @Suppress("SwallowedException")
    fun unwrap(wrapped: WrappedVdek, kekBytes: ByteArray, aad: Aad): KeysetHandle {
        val kekAead = KekImporter.importKek(kekBytes)
        return try {
            TinkProtoKeysetFormat.parseEncryptedKeyset(wrapped.bytes, kekAead, aad.bytes)
        } catch (e: GeneralSecurityException) {
            throw CryptoError.InvalidCredentials
        } catch (e: IOException) {
            throw CryptoError.InvalidCredentials
        }
    }
}
