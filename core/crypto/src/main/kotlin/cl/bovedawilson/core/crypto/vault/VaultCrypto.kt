package cl.bovedawilson.core.crypto.vault

import cl.bovedawilson.core.common.memory.SecureBytes
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.recovery.RecoveryEntropy
import cl.bovedawilson.core.crypto.recovery.RecoveryPhrase
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion

/** Resultado de [VaultCrypto.createVault]: la bóveda ya desbloqueada, su envoltorio para
 * persistir y la frase de recuperación, que solo existe en memoria en este momento. */
class CreatedVault(
    val vault: UnlockedVault,
    val record: VaultRecord,
    val recoveryPhrase: RecoveryPhrase,
)

/** Resultado de [VaultCrypto.regenerateRecovery]: el envoltorio actualizado y la frase nueva. */
class RegeneratedRecovery(
    val record: VaultRecord,
    val recoveryPhrase: RecoveryPhrase,
)

/** Resultado de una restauración de respaldo con los dos caminos de acceso reemitidos. */
class RestoredVault(
    val vault: UnlockedVault,
    val record: VaultRecord,
    val recoveryPhrase: RecoveryPhrase?,
) {
    override fun toString(): String = "RestoredVault(recoveryPhrase=${recoveryPhrase != null})"
}

/**
 * Resultado de [VaultCrypto.wrapForBiometric]: el envoltorio para persistir en
 * `BiometricUnlockEntity` y la `BiometricKEK` en claro, que quien llama debe usar de
 * inmediato para cifrarla con el Cipher autenticado por biometría del Keystore y **cerrar**
 * ([SecureBytes.close]) apenas termine — nunca persistirla sin envolver
 * (`docs/key-lifecycle.md` §9).
 */
class BiometricEnrollment(
    val wrap: BiometricWrap,
    val kek: SecureBytes,
)

/**
 * Operaciones de alto nivel del núcleo criptográfico descritas en `CRYPTOGRAPHY.md`:
 * crear la bóveda, desbloquear por contraseña o por frase, cambiar la contraseña maestra y
 * regenerar la recuperación. Todas devuelven [AppResult] y borran su material transitorio
 * (KEK derivadas, entropía) en `finally`, incluso ante excepción. La derivación de KEK y el
 * envoltorio/desenvoltorio de la VDEK viven en [VaultWrapping].
 */
object VaultCrypto {
    private const val INITIAL_EPOCH = 1

    fun createVault(vaultId: String, password: CharArray): AppResult<CreatedVault, CryptoError> = resultOf {
        val vault = UnlockedVault.withNewVdek()
        val vdekHandle = vault.handleForWrapping()

        val passwordWrap = VaultWrapping.wrapPassword(vdekHandle, password, vaultId, INITIAL_EPOCH)

        val recoveryEntropy = RecoveryEntropy.generate()
        try {
            val recoverySalt = VaultWrapping.newRandomSecret(VaultWrapping.RECOVERY_SALT_BYTES)
            val recoveryWrap = VaultWrapping.wrapRecovery(
                vdekHandle,
                recoveryEntropy,
                recoverySalt,
                vaultId,
                INITIAL_EPOCH,
            )

            VaultWrapping.verifySameVdek(
                VaultWrapping.unwrapPassword(vaultId, passwordWrap, password),
                VaultWrapping.unwrapRecovery(vaultId, recoveryWrap, recoveryEntropy),
            )

            val record = VaultRecord(vaultId, CryptoVersion.V1, SchemaVersion.V1, passwordWrap, recoveryWrap)
            CreatedVault(vault, record, recoveryEntropy.toPhrase())
        } finally {
            recoveryEntropy.close()
        }
    }

    fun unlockWithPassword(record: VaultRecord, password: CharArray): AppResult<UnlockedVault, CryptoError> = resultOf {
        checkVersion(record)
        val handle = VaultWrapping.unwrapPassword(record.vaultId, record.password, password)
        UnlockedVault.fromHandle(handle)
    }

    fun unlockWithRecovery(record: VaultRecord, words: List<String>): AppResult<UnlockedVault, CryptoError> = resultOf {
        checkVersion(record)
        val entropy = RecoveryEntropy.fromWords(words)
        try {
            val handle = VaultWrapping.unwrapRecovery(record.vaultId, record.recovery, entropy)
            UnlockedVault.fromHandle(handle)
        } finally {
            entropy.close()
        }
    }

    fun changeMasterPassword(
        record: VaultRecord,
        currentPassword: CharArray,
        newPassword: CharArray,
    ): AppResult<VaultRecord, CryptoError> = resultOf {
        checkVersion(record)
        val vdekHandle = VaultWrapping.unwrapPassword(record.vaultId, record.password, currentPassword)
        val newEpoch = record.password.epoch + 1
        val newPasswordWrap = VaultWrapping.wrapPassword(vdekHandle, newPassword, record.vaultId, newEpoch)

        VaultWrapping.verifySameVdek(
            vdekHandle,
            VaultWrapping.unwrapPassword(record.vaultId, newPasswordWrap, newPassword),
        )

        VaultRecord(record.vaultId, record.cryptoVersion, record.schemaVersion, newPasswordWrap, record.recovery)
    }

    fun regenerateRecovery(
        record: VaultRecord,
        currentPassword: CharArray,
    ): AppResult<RegeneratedRecovery, CryptoError> = resultOf {
        checkVersion(record)
        val vdekHandle = VaultWrapping.unwrapPassword(record.vaultId, record.password, currentPassword)
        val newEntropy = RecoveryEntropy.generate()
        try {
            val newSalt = VaultWrapping.newRandomSecret(VaultWrapping.RECOVERY_SALT_BYTES)
            val newEpoch = record.recovery.epoch + 1
            val newRecoveryWrap = VaultWrapping.wrapRecovery(vdekHandle, newEntropy, newSalt, record.vaultId, newEpoch)

            VaultWrapping.verifySameVdek(
                vdekHandle,
                VaultWrapping.unwrapRecovery(record.vaultId, newRecoveryWrap, newEntropy),
            )

            val newRecord = VaultRecord(
                vaultId = record.vaultId,
                cryptoVersion = record.cryptoVersion,
                schemaVersion = record.schemaVersion,
                password = record.password,
                recovery = newRecoveryWrap,
            )
            RegeneratedRecovery(newRecord, newEntropy.toPhrase())
        } finally {
            newEntropy.close()
        }
    }

    /**
     * Restaura un respaldo autenticado con la contraseña del propio respaldo. Ambos caminos
     * se reemiten con salts nuevos y epochs posteriores antes de devolver el resultado; la
     * frase nueva solo vive en el resultado transitorio para que la interfaz la muestre una vez.
     */
    fun restoreWithPassword(
        record: VaultRecord,
        password: CharArray,
        passwordEpochFloor: Int = 0,
        recoveryEpochFloor: Int = 0,
    ): AppResult<RestoredVault, CryptoError> = resultOf {
        checkVersion(record)
        require(passwordEpochFloor >= 0 && recoveryEpochFloor >= 0) { "invalid_epoch_floor" }
        val vdekHandle = VaultWrapping.unwrapPassword(record.vaultId, record.password, password)
        val newEntropy = RecoveryEntropy.generate()
        try {
            val newPasswordWrap = VaultWrapping.wrapPassword(
                vdekHandle,
                password,
                record.vaultId,
                nextEpoch(maxOf(record.password.epoch, passwordEpochFloor)),
            )
            val newRecoverySalt = VaultWrapping.newRandomSecret(VaultWrapping.RECOVERY_SALT_BYTES)
            val newRecoveryWrap = VaultWrapping.wrapRecovery(
                vdekHandle,
                newEntropy,
                newRecoverySalt,
                record.vaultId,
                nextEpoch(maxOf(record.recovery.epoch, recoveryEpochFloor)),
            )
            VaultWrapping.verifySameVdek(
                VaultWrapping.unwrapPassword(record.vaultId, newPasswordWrap, password),
                VaultWrapping.unwrapRecovery(record.vaultId, newRecoveryWrap, newEntropy),
            )
            RestoredVault(
                vault = UnlockedVault.fromHandle(vdekHandle),
                record = VaultRecord(
                    vaultId = record.vaultId,
                    cryptoVersion = record.cryptoVersion,
                    schemaVersion = record.schemaVersion,
                    password = newPasswordWrap,
                    recovery = newRecoveryWrap,
                ),
                recoveryPhrase = newEntropy.toPhrase(),
            )
        } finally {
            newEntropy.close()
        }
    }

    /**
     * Restaura un respaldo autenticado con la frase y exige una contraseña maestra nueva.
     * La frase se conserva como camino de recuperación, pero se reenvuelve con salt/epoch
     * nuevos junto con el camino de contraseña. Los dos envoltorios se comparan antes de
     * devolver el registro.
     */
    fun restoreWithRecovery(
        record: VaultRecord,
        phrase: List<String>,
        newPassword: CharArray,
        passwordEpochFloor: Int = 0,
        recoveryEpochFloor: Int = 0,
    ): AppResult<RestoredVault, CryptoError> = resultOf {
        checkVersion(record)
        require(passwordEpochFloor >= 0 && recoveryEpochFloor >= 0) { "invalid_epoch_floor" }
        val entropy = RecoveryEntropy.fromWords(phrase)
        try {
            val vdekHandle = VaultWrapping.unwrapRecovery(record.vaultId, record.recovery, entropy)
            val newPasswordWrap = VaultWrapping.wrapPassword(
                vdekHandle,
                newPassword,
                record.vaultId,
                nextEpoch(maxOf(record.password.epoch, passwordEpochFloor)),
            )
            val newRecoverySalt = VaultWrapping.newRandomSecret(VaultWrapping.RECOVERY_SALT_BYTES)
            val newRecoveryWrap = VaultWrapping.wrapRecovery(
                vdekHandle,
                entropy,
                newRecoverySalt,
                record.vaultId,
                nextEpoch(maxOf(record.recovery.epoch, recoveryEpochFloor)),
            )
            VaultWrapping.verifySameVdek(
                VaultWrapping.unwrapPassword(record.vaultId, newPasswordWrap, newPassword),
                VaultWrapping.unwrapRecovery(record.vaultId, newRecoveryWrap, entropy),
            )
            RestoredVault(
                vault = UnlockedVault.fromHandle(vdekHandle),
                record = VaultRecord(
                    vaultId = record.vaultId,
                    cryptoVersion = record.cryptoVersion,
                    schemaVersion = record.schemaVersion,
                    password = newPasswordWrap,
                    recovery = newRecoveryWrap,
                ),
                recoveryPhrase = null,
            )
        } finally {
            entropy.close()
        }
    }

    /**
     * Envuelve la VDEK de una sesión ya desbloqueada con una `BiometricKEK` aleatoria nueva
     * (`docs/key-lifecycle.md` §9). No toca `VaultRecord`: el resultado se persiste aparte,
     * en `BiometricUnlockEntity`, local y nunca sincronizado. Verifica, igual que
     * contraseña y recuperación, que el envoltorio recién creado abre la misma VDEK antes
     * de devolver la KEK en claro.
     */
    fun wrapForBiometric(
        vault: UnlockedVault,
        alias: String,
        vaultId: String,
        epoch: Int,
    ): AppResult<BiometricEnrollment, CryptoError> = resultOf {
        val vdekHandle = vault.handleForWrapping()
        val kek = SecureBytes(VaultWrapping.newRandomSecret(VaultWrapping.BIOMETRIC_KEK_BYTES))
        var ownershipTransferred = false
        try {
            val wrap = kek.withBytes { kekBytes ->
                val candidate = VaultWrapping.wrapBiometric(vdekHandle, kekBytes, alias, vaultId, epoch)
                VaultWrapping.verifySameVdek(
                    vdekHandle,
                    VaultWrapping.unwrapBiometric(vaultId, candidate, kekBytes)
                )
                candidate
            }
            ownershipTransferred = true
            BiometricEnrollment(wrap, kek)
        } finally {
            if (!ownershipTransferred) kek.close()
        }
    }

    /**
     * Desbloquea la VDEK a partir de una `BiometricKEK` ya recuperada del blob local del
     * Keystore (el Cipher, la autenticación y el desenvuelto de esa KEK son responsabilidad
     * de la capa que gestiona el Keystore, fuera de `:core:crypto`). No hay `VaultRecord`
     * que versionar aquí: la versión ya quedó fijada al activar la biometría.
     */
    fun unlockWithBiometric(
        vaultId: String,
        wrap: BiometricWrap,
        biometricKek: ByteArray,
    ): AppResult<UnlockedVault, CryptoError> = resultOf {
        val handle = VaultWrapping.unwrapBiometric(vaultId, wrap, biometricKek)
        UnlockedVault.fromHandle(handle)
    }
}

private fun checkVersion(record: VaultRecord) {
    if (record.cryptoVersion != CryptoVersion.V1) throw CryptoError.UnsupportedVersion
    if (record.schemaVersion != SchemaVersion.V1) throw CryptoError.UnsupportedVersion
}

private fun nextEpoch(epoch: Int): Int {
    check(epoch < Int.MAX_VALUE) { "epoch_exhausted" }
    return epoch + 1
}

/** Adapta errores tipificados del núcleo sin aumentar la superficie pública de [VaultCrypto]. */
private inline fun <T> resultOf(block: () -> T): AppResult<T, CryptoError> = try {
    AppResult.Success(block())
} catch (e: CryptoError) {
    AppResult.Failure(e)
}
