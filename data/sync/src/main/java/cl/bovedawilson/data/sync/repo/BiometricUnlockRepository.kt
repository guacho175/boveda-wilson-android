package cl.bovedawilson.data.sync.repo

import android.database.SQLException
import androidx.biometric.BiometricPrompt
import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.memory.Wipe
import cl.bovedawilson.core.common.result.AppError
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.vault.BiometricWrap
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.wrap.WrappedVdek
import cl.bovedawilson.data.local.dao.BiometricUnlockDao
import cl.bovedawilson.data.local.entity.BiometricUnlockEntity
import cl.bovedawilson.data.local.store.VaultMetaStore
import cl.bovedawilson.data.sync.biometric.BiometricUnlock
import cl.bovedawilson.data.sync.engine.ConflictResolver
import cl.bovedawilson.data.sync.mapper.VaultRecordMapper
import cl.bovedawilson.data.sync.session.UnlockLease
import cl.bovedawilson.data.sync.session.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/**
 * Ciclo de vida del desbloqueo biométrico (`docs/key-lifecycle.md` §9, ADR-028): activar,
 * desbloquear y desactivar. Separado de [VaultRepository] para que ninguna de las dos
 * clases supere el límite de funciones por clase de Detekt; comparten `vault_meta` mediante
 * [metaStore] pero el blob biométrico vive aparte, en `BiometricUnlockEntity`, local y
 * nunca sincronizado.
 */
// Activar, cancelar, desbloquear, invalidar y desactivar forman un único ciclo de vida
// transaccional; separarlo ocultaría la limpieza cruzada entre Keystore y Room.
@Suppress("TooManyFunctions")
class BiometricUnlockRepository(
    private val metaStore: VaultMetaStore,
    private val session: VaultSession,
    private val dispatchers: AppDispatchers,
    private val biometricUnlockDao: BiometricUnlockDao,
    private val biometricUnlock: BiometricUnlock,
    private val conflictResolver: ConflictResolver
) {
    @Volatile
    private var preparedUnlockLease: UnlockLease? = null

    private companion object {
        const val BIOMETRIC_ROW_ID = 1
    }

    /** Hardware biométrico fuerte disponible en este dispositivo. No dice si el usuario ya
     * activó el desbloqueo biométrico para esta bóveda; para eso, [hasBiometricEnrollment]. */
    fun isBiometricHardwareAvailable(): Boolean = biometricUnlock.canAuthenticate()

    suspend fun hasBiometricEnrollment(): Boolean = withContext(dispatchers.io) {
        biometricUnlockDao.get() != null
    }

    /**
     * Primer paso para activar el desbloqueo biométrico: un `CryptoObject` del Keystore
     * listo para que la UI lo autentique con `BiometricPrompt`. Exige la bóveda ya
     * desbloqueada.
     */
    suspend fun prepareEnrollmentCipher(): BiometricPrompt.CryptoObject? {
        if (session.getVault() == null) return null
        val cryptoObject = biometricUnlock.createEncryptCryptoObject()
        if (cryptoObject == null) cancelPreparedEnrollment()
        return cryptoObject
    }

    /**
     * Segundo paso: con el `Cipher` ya autenticado por biometría, envuelve la VDEK de la
     * sesión actual con una `BiometricKEK` nueva, cifra esa KEK con el propio `Cipher` y
     * persiste el conjunto completo de forma atómica en `BiometricUnlockEntity`.
     */
    /** Los fallos JCA/Room se reducen a AppError sin conservar causas potencialmente sensibles. */
    @Suppress("SwallowedException")
    suspend fun completeEnrollment(cipher: Cipher): AppResult<Unit, AppError> =
        withContext(dispatchers.default) {
            try {
                completeEnrollmentAuthenticated(cipher)
            } catch (e: CancellationException) {
                clearEnrollment(lockSession = false)
                throw e
            } catch (e: GeneralSecurityException) {
                clearEnrollment(lockSession = false)
                AppResult.Failure(AppError.OperationFailed)
            } catch (e: SQLException) {
                clearEnrollment(lockSession = false)
                AppResult.Failure(AppError.OperationFailed)
            } catch (e: IllegalArgumentException) {
                clearEnrollment(lockSession = false)
                AppResult.Failure(AppError.OperationFailed)
            } catch (e: IllegalStateException) {
                clearEnrollment(lockSession = false)
                AppResult.Failure(AppError.OperationFailed)
            }
        }

    private suspend fun completeEnrollmentAuthenticated(cipher: Cipher): AppResult<Unit, AppError> {
        val vault = session.getVault()
        val entity = metaStore.getMeta()
        if (vault == null || entity == null) return AppResult.Failure(AppError.OperationFailed)
        val nextEpoch = (biometricUnlockDao.get()?.biometricWrapEpoch ?: 0) + 1

        return VaultCrypto.wrapForBiometric(
            vault,
            BiometricUnlock.KEY_ALIAS_BIOMETRIC,
            entity.vaultId,
            nextEpoch
        ).fold(
            onSuccess = { enrollment ->
                try {
                    // `SecureBytes.withBytes` recibe un lambda no-suspend a propósito
                    // (no debe sobrevivir la referencia más allá del bloque).
                    val wrappedKek = enrollment.kek.withBytes { kekBytes ->
                        val aad = AadBuilder.forBiometricKek(
                            entity.vaultId,
                            BiometricUnlock.KEY_ALIAS_BIOMETRIC,
                            CryptoVersion.V1
                        )
                        cipher.updateAAD(aad.bytes)
                        cipher.doFinal(kekBytes)
                    }
                    biometricUnlockDao.insertOrUpdate(
                        BiometricUnlockEntity(
                            id = BIOMETRIC_ROW_ID,
                            keyAlias = BiometricUnlock.KEY_ALIAS_BIOMETRIC,
                            wrappedBiometricKek = wrappedKek,
                            biometricWrappedVdek = enrollment.wrap.wrappedVdek.bytes,
                            biometricWrapEpoch = enrollment.wrap.epoch,
                            iv = cipher.iv,
                            strongBoxBacked = biometricUnlock.lastKeyWasStrongBoxBacked,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    AppResult.Success(Unit)
                } finally {
                    enrollment.kek.close()
                }
            },
            onFailure = { AppResult.Failure(it.toAppError()) }
        )
    }

    /** Limpia la clave preparada si el usuario cancela el prompt antes del commit. */
    suspend fun cancelPreparedEnrollment() = withContext(dispatchers.io) {
        if (biometricUnlockDao.get() == null) biometricUnlock.invalidateBiometricKey()
    }

    /**
     * Primer paso para desbloquear con biometría: si hay un conjunto activo, un
     * `CryptoObject` en modo descifrado con el IV guardado, listo para autenticar. `null`
     * si no hay biometría activada o la clave del Keystore ya no es válida (se invalidó por
     * reinscripción: hay que borrar el conjunto y exigir contraseña).
     */
    suspend fun prepareUnlockCipher(): BiometricPrompt.CryptoObject? = withContext(dispatchers.io) {
        val lease = session.beginUnlock() ?: return@withContext null
        val stored = biometricUnlockDao.get() ?: return@withContext null
        if (biometricUnlock.isDeviceLocked()) {
            preparedUnlockLease = null
            return@withContext null
        }
        if (!biometricUnlock.isBiometricKeyValid()) {
            clearEnrollment(lockSession = true)
            return@withContext null
        }
        val cryptoObject = biometricUnlock.createDecryptCryptoObject(stored.iv)
        // `getKey()` puede devolver una referencia todavía presente y `Cipher.init()`
        // descubrir recién entonces una invalidación por reinscripción biométrica. Un
        // `null` aquí invalida el atajo completo para no ofrecerlo en bucle.
        if (cryptoObject == null) {
            preparedUnlockLease = null
            // `Cipher.init()` también puede fallar si el dispositivo se bloqueó entre la
            // comprobación inicial y esta operación. Ese estado transitorio no invalida la clave.
            if (!biometricUnlock.isDeviceLocked()) clearEnrollment(lockSession = true)
        } else {
            preparedUnlockLease = lease
        }
        cryptoObject
    }

    /**
     * Segundo paso: con el `Cipher` ya autenticado, recupera la `BiometricKEK`, desenvuelve
     * la VDEK y abre la sesión. Ante cualquier fallo criptográfico (clave incorrecta,
     * envoltorio alterado) borra el conjunto local en vez de dejarlo reintentable: coincide
     * con el tratamiento de invalidación de `docs/key-lifecycle.md` §3.
     */
    suspend fun unlockWithBiometric(cipher: Cipher): AppResult<Unit, AppError> =
        withContext(dispatchers.default) {
            val lease = preparedUnlockLease.also { preparedUnlockLease = null }
                ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            if (!session.isUnlockLeaseValid(lease)) {
                return@withContext AppResult.Failure(AppError.OperationFailed)
            }
            val stored = biometricUnlockDao.get() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            val entity = metaStore.getMeta() ?: return@withContext AppResult.Failure(AppError.OperationFailed)
            val kekBytes = decryptStoredKek(cipher, entity.vaultId, stored) ?: run {
                clearEnrollment(lockSession = true)
                return@withContext AppResult.Failure(AppError.InvalidCredentials)
            }
            unlockVdekWithKek(entity.vaultId, stored, kekBytes, lease)
        }

    /** La causa original no se encadena: un fallo JCA solo confirma que la clave
     * o el blob no coinciden o ya no son utilizables, y `CRYPTOGRAPHY.md` §13 exige que las excepciones
     * criptográficas no lleven material sensible ni en el mensaje ni en la causa. */
    @Suppress("SwallowedException")
    private fun decryptStoredKek(cipher: Cipher, vaultId: String, stored: BiometricUnlockEntity): ByteArray? = try {
        val aad = AadBuilder.forBiometricKek(vaultId, stored.keyAlias, CryptoVersion.V1)
        cipher.updateAAD(aad.bytes)
        cipher.doFinal(stored.wrappedBiometricKek)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    private suspend fun unlockVdekWithKek(
        vaultId: String,
        stored: BiometricUnlockEntity,
        kekBytes: ByteArray,
        lease: UnlockLease
    ): AppResult<Unit, AppError> = try {
        val wrap = BiometricWrap(
            alias = stored.keyAlias,
            wrappedVdek = WrappedVdek(stored.biometricWrappedVdek),
            epoch = stored.biometricWrapEpoch
        )
        val result = VaultCrypto.unlockWithBiometric(vaultId, wrap, kekBytes).fold(
            onSuccess = { vault ->
                finishUnlock(vault, vaultId, lease)
            },
            onFailure = { AppResult.Failure(it.toAppError()) }
        )
        if (result is AppResult.Failure) clearEnrollment(lockSession = true)
        result
    } finally {
        Wipe.bytes(kekBytes)
    }

    // La causa se omite a propósito: podría incluir datos de la operación criptográfica.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun finishUnlock(
        vault: UnlockedVault,
        vaultId: String,
        lease: UnlockLease
    ): AppResult<Unit, AppError> {
        return try {
            conflictResolver.resolveAllPending(vault, vaultId) {
                session.isUnlockLeaseValid(lease)
            }
            if (session.tryUnlock(lease, vault, vaultId)) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(AppError.OperationFailed)
            }
        } catch (e: CancellationException) {
            session.lock()
            throw e
        } catch (e: Exception) {
            session.lock()
            AppResult.Failure(AppError.OperationFailed)
        }
    }

    /**
     * Desactiva el desbloqueo biométrico: borra la clave del Keystore y el registro local.
     * El blob es desechable, así que esto nunca afecta a la bóveda en sí
     * (`docs/key-lifecycle.md` §9). Exige la contraseña maestra como reautenticación
     * (`SECURITY.md` §5); no se usa para ninguna operación criptográfica,
     * solo para confirmar que quien desactiva es quien puede abrir la bóveda.
     */
    suspend fun disableBiometric(password: CharArray): AppResult<Unit, AppError> =
        withContext(dispatchers.default) {
            try {
                val record = metaStore.getMeta()?.let(VaultRecordMapper::toRecord)
                    ?: return@withContext AppResult.Failure(AppError.OperationFailed)
                VaultCrypto.unlockWithPassword(record, password).fold(
                    onSuccess = {
                        clearEnrollment(lockSession = false)
                        AppResult.Success(Unit)
                    },
                    onFailure = { AppResult.Failure(it.toAppError()) }
                )
            } finally {
                Wipe.chars(password)
            }
        }

    private suspend fun clearEnrollment(lockSession: Boolean) {
        biometricUnlock.invalidateBiometricKey()
        biometricUnlockDao.delete()
        if (lockSession) session.lock()
    }
}
