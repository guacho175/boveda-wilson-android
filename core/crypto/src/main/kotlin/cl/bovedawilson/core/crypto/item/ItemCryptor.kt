package cl.bovedawilson.core.crypto.item

import cl.bovedawilson.core.crypto.aead.Aad
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.error.CryptoError
import com.google.crypto.tink.Aead
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException

/**
 * Cifra y descifra el JSON canónico de [ItemPayload] directamente con la VDEK (ADR-010).
 * Interno: la serialización a bytes de plaintext no sale de `:core:crypto`
 * según el contrato versionado de `CRYPTOGRAPHY.md`.
 */
internal object ItemCryptor {
    private val json = Json { encodeDefaults = true }

    fun encrypt(aead: Aead, payload: ItemPayload, aad: Aad): Ciphertext {
        val plaintext = json.encodeToString(ItemPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        val encrypted: ByteArray
        try {
            encrypted = aead.encrypt(plaintext, aad.bytes)
        } finally {
            plaintext.fill(0)
        }
        return Ciphertext.fromEncryption(encrypted)
    }

    /**
     * Ninguna de las dos excepciones capturadas se encadena como causa: podrían llevar
     * bytes del ciphertext o del intento de deserialización, y `CRYPTOGRAPHY.md` §13
     * exige que las excepciones criptográficas no lleven material sensible, ni en el
     * mensaje ni en la causa.
     */
    @Suppress("SwallowedException")
    fun decrypt(aead: Aead, ciphertext: Ciphertext, aad: Aad): ItemPayload {
        val plaintext = try {
            aead.decrypt(ciphertext.bytes, aad.bytes)
        } catch (e: GeneralSecurityException) {
            throw CryptoError.IntegrityFailure
        }
        try {
            return json.decodeFromString(ItemPayload.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw CryptoError.MalformedInput
        } finally {
            plaintext.fill(0)
        }
    }
}
