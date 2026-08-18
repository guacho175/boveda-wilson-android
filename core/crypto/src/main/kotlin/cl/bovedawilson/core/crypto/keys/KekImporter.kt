package cl.bovedawilson.core.crypto.keys

import cl.bovedawilson.core.crypto.error.CryptoError
import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes

/**
 * Importa 32 bytes de material externo (PasswordKEK, RecoveryKEK, BiometricKEK) como una
 * clave AES256-GCM de Tink **sin prefijo** (`CRYPTOGRAPHY.md` §8) y devuelve su primitiva
 * `Aead`. Interno: ninguna firma pública del módulo expone `Aead` ni `KeysetHandle`
 * (`docs/architecture.md` §2).
 */
internal object KekImporter {
    private const val KEK_SIZE_BYTES = 32
    private const val GCM_IV_SIZE_BYTES = 12
    private const val GCM_TAG_SIZE_BYTES = 16

    init {
        AeadConfig.register()
    }

    fun importKek(kekBytes: ByteArray): Aead {
        if (kekBytes.size != KEK_SIZE_BYTES) throw CryptoError.InternalError
        val parameters = AesGcmParameters.builder()
            .setIvSizeBytes(GCM_IV_SIZE_BYTES)
            .setKeySizeBytes(KEK_SIZE_BYTES)
            .setTagSizeBytes(GCM_TAG_SIZE_BYTES)
            .setVariant(AesGcmParameters.Variant.NO_PREFIX)
            .build()
        val key = AesGcmKey.builder()
            .setParameters(parameters)
            .setKeyBytes(SecretBytes.copyFrom(kekBytes, InsecureSecretKeyAccess.get()))
            .build()
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withRandomId().makePrimary())
            .build()
        return handle.getPrimitive(Aead::class.java)
    }
}
