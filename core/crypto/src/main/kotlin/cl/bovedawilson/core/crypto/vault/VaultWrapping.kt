package cl.bovedawilson.core.crypto.vault

import cl.bovedawilson.core.common.memory.useAsUtf8Bytes
import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.hkdf.Hkdf
import cl.bovedawilson.core.crypto.hkdf.HkdfContext
import cl.bovedawilson.core.crypto.kdf.Argon2idPasswordKdf
import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.kdf.KdfPolicy
import cl.bovedawilson.core.crypto.recovery.RecoveryEntropy
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.wrap.VdekWrapper
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Deriva las KEK, construye la AAD y envuelve/desenvuelve la VDEK por cada camino de
 * acceso. Separado de [VaultCrypto] para que cada tipo se mantenga dentro del límite de
 * funciones por clase; ambos son internos a `:core:crypto`.
 */
internal object VaultWrapping {
    const val RECOVERY_SALT_BYTES = 32
    const val BIOMETRIC_KEK_BYTES = 32
    private val secureRandom = SecureRandom()

    /** Bytes de `SecureRandom` para material nuevo: el salt de recuperación o la
     * `BiometricKEK` (`docs/key-lifecycle.md` §2, §9). Quien la recibe es responsable de
     * borrarla cuando termine de usarla. Una sola función para las dos, en vez de una por
     * cada camino de acceso, para no acumular funciones triviales en este objeto. */
    fun newRandomSecret(sizeBytes: Int): ByteArray {
        val secret = ByteArray(sizeBytes)
        secureRandom.nextBytes(secret)
        return secret
    }

    fun wrapBiometric(
        vdekHandle: KeysetHandle,
        biometricKek: ByteArray,
        alias: String,
        vaultId: String,
        epoch: Int,
    ): BiometricWrap {
        val aad = AadBuilder.forBiometricWrap(vaultId, alias, CryptoVersion.V1, epoch)
        return BiometricWrap(alias, VdekWrapper.wrap(vdekHandle, biometricKek, aad), epoch)
    }

    fun unwrapBiometric(vaultId: String, wrap: BiometricWrap, biometricKek: ByteArray): KeysetHandle {
        val aad = AadBuilder.forBiometricWrap(vaultId, wrap.alias, CryptoVersion.V1, wrap.epoch)
        return VdekWrapper.unwrap(wrap.wrappedVdek, biometricKek, aad)
    }

    fun wrapPassword(vdekHandle: KeysetHandle, password: CharArray, vaultId: String, epoch: Int): PasswordWrap {
        val params = KdfPolicy.newProductionParameters()
        val kek = passwordKek(password, params)
        try {
            val aad = AadBuilder.forPasswordWrap(vaultId, CryptoVersion.V1, params, epoch)
            return PasswordWrap(params, VdekWrapper.wrap(vdekHandle, kek, aad), epoch)
        } finally {
            kek.fill(0)
        }
    }

    fun unwrapPassword(vaultId: String, wrap: PasswordWrap, password: CharArray): KeysetHandle {
        val kek = passwordKek(password, wrap.parameters)
        try {
            val aad = AadBuilder.forPasswordWrap(vaultId, CryptoVersion.V1, wrap.parameters, wrap.epoch)
            return VdekWrapper.unwrap(wrap.wrappedVdek, kek, aad)
        } finally {
            kek.fill(0)
        }
    }

    fun wrapRecovery(
        vdekHandle: KeysetHandle,
        entropy: RecoveryEntropy,
        salt: ByteArray,
        vaultId: String,
        epoch: Int,
    ): RecoveryWrap {
        requireRecoverySaltLength(salt)
        return entropy.withBytes { raw ->
            val kek = Hkdf.derive(raw, salt, HkdfContext.RECOVERY_KEK)
            try {
                val aad = AadBuilder.forRecoveryWrap(vaultId, CryptoVersion.V1, salt, epoch)
                RecoveryWrap(salt, VdekWrapper.wrap(vdekHandle, kek, aad), epoch)
            } finally {
                kek.fill(0)
            }
        }
    }

    fun unwrapRecovery(vaultId: String, wrap: RecoveryWrap, entropy: RecoveryEntropy): KeysetHandle {
        requireRecoverySaltLength(wrap.salt)
        return entropy.withBytes { raw ->
            val kek = Hkdf.derive(raw, wrap.salt, HkdfContext.RECOVERY_KEK)
            try {
                val aad = AadBuilder.forRecoveryWrap(vaultId, CryptoVersion.V1, wrap.salt, wrap.epoch)
                VdekWrapper.unwrap(wrap.wrappedVdek, kek, aad)
            } finally {
                kek.fill(0)
            }
        }
    }

    /** Igual tratamiento que [KdfPolicy.verify] para el salt de recuperación: se valida
     * tanto al crear un envoltorio nuevo como al leer uno recibido de fuera. */
    private fun requireRecoverySaltLength(salt: ByteArray) {
        if (salt.size != RECOVERY_SALT_BYTES) throw CryptoError.MalformedInput
    }

    /**
     * Antes de persistir una creación o una regeneración, comprueba que los dos
     * envoltorios abren la **misma** VDEK (`CRYPTOGRAPHY.md` §12, G-54): compara el
     * keyset en claro exportado de cada `KeysetHandle`, byte a byte, y lo borra de
     * inmediato.
     *
     * La causa original de un fallo de Tink al serializar podría llevar bytes de
     * material de clave; `CRYPTOGRAPHY.md` §13 exige que las excepciones criptográficas
     * no lleven material sensible ni en el mensaje ni en la causa, así que aquí no se
     * encadena a propósito.
     */
    @Suppress("SwallowedException")
    fun verifySameVdek(a: KeysetHandle, b: KeysetHandle) {
        val (serializedA, serializedB) = try {
            TinkProtoKeysetFormat.serializeKeyset(a, InsecureSecretKeyAccess.get()) to
                TinkProtoKeysetFormat.serializeKeyset(b, InsecureSecretKeyAccess.get())
        } catch (e: GeneralSecurityException) {
            throw CryptoError.InternalError
        }
        try {
            if (!serializedA.contentEquals(serializedB)) throw CryptoError.InternalError
        } finally {
            serializedA.fill(0)
            serializedB.fill(0)
        }
    }

    private fun passwordKek(password: CharArray, params: KdfParameters): ByteArray = password.useAsUtf8Bytes { utf8 ->
        val argonOut = Argon2idPasswordKdf().derive(utf8, params)
        try {
            Hkdf.derive(argonOut, params.salt, HkdfContext.PASSWORD_KEK)
        } finally {
            argonOut.fill(0)
        }
    }
}
