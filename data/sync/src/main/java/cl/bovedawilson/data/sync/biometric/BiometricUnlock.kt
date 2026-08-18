package cl.bovedawilson.data.sync.biometric

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import cl.bovedawilson.core.common.log.Redact
import cl.bovedawilson.core.common.log.SecureLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Gestiona la clave biométrica de Android Keystore que protege localmente una
 * `BiometricKEK` (`docs/key-lifecycle.md` §9, ADR-028): no exportable, autenticación de
 * usuario requerida por operación, `BIOMETRIC_STRONG` sin credencial del dispositivo.
 *
 * Vive en `:data:sync` y no en `:core:crypto` (hallazgo H-01,
 * Depende de `androidx.biometric`, una librería de
 * interfaz, y el núcleo criptográfico no puede depender de eso
 * (`docs/architecture.md` §3). El envoltorio Tink de la VDEK con la `BiometricKEK`
 * sigue viviendo en `:core:crypto` (`VaultCrypto.wrapForBiometric`/`unlockWithBiometric`);
 * esta clase solo gestiona la clave del Keystore que protege esa KEK.
 */
// Adaptador cohesivo de Android Keystore: cada función encapsula una operación JCA/biométrica
// distinta y mantenerlas juntas hace verificable el ciclo de vida de una única clave.
@Suppress("TooManyFunctions")
class BiometricUnlock internal constructor(
    private val context: Context,
    private val keyAlias: String = KEY_ALIAS_BIOMETRIC
) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)

    /** Nivel observado mediante `KeyInfo` para la última clave creada o recuperada.
     * No se infiere del intento de generación y solo se expone para pruebas. */
    var lastKeyWasStrongBoxBacked: Boolean = false
        private set

    init {
        keyStore.load(null)
    }

    /** true si el dispositivo tiene hardware biométrico fuerte inscrito y disponible. */
    fun canAuthenticate(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Un bloqueo transitorio del dispositivo no invalida ni degrada la clave biométrica. */
    fun isDeviceLocked(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceLocked

    /**
     * Crea o recupera la clave biométrica en Keystore.
     *
     * - No exportable
     * - Autenticación de usuario requerida
     * - Intenta StrongBox con fallback a TEE
     * - Exige BIOMETRIC_STRONG sin DEVICE_CREDENTIAL
     */
    @Suppress("Deprecation")
    fun createOrGetBiometricKey(): SecretKey? = try {
        val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) {
            verifyHardwareLevel(existingKey, expectedLevel = null)
        } else {
            generateBiometricKey(keyAlias)
        }
    } catch (e: java.security.NoSuchAlgorithmException) {
        logFailure("error creating biometric key", e)
        null
    } catch (e: java.security.NoSuchProviderException) {
        logFailure("error creating biometric key", e)
        null
    } catch (e: java.security.InvalidKeyException) {
        logFailure("error creating biometric key", e)
        null
    } catch (e: java.security.InvalidAlgorithmParameterException) {
        logFailure("error creating biometric key", e)
        null
    } catch (e: java.security.spec.InvalidKeySpecException) {
        logFailure("error creating biometric key", e)
        null
    } catch (e: java.security.KeyStoreException) {
        logFailure("error creating biometric key", e)
        null
    }

    /** `setIsStrongBoxBacked(true)` no cae solo a TEE: si el dispositivo no tiene
     * StrongBox, `generateKey()` lanza `StrongBoxUnavailableException` y hay que
     * reintentar sin ese atributo (ADR-012/028). Sin este `catch`, la creación de la clave
     * fallaría siempre en el dispositivo de referencia del proyecto (sin StrongBox). La
     * excepción se descarta a propósito: es la señal esperada de "sin StrongBox", no un
     * fallo que investigar. */
    @Suppress("SwallowedException")
    private fun generateBiometricKey(keyAlias: String): SecretKey = try {
        buildBiometricKey(keyAlias, strongBox = true)
    } catch (e: android.security.keystore.StrongBoxUnavailableException) {
        buildBiometricKey(keyAlias, strongBox = false)
    }

    private fun buildBiometricKey(keyAlias: String, strongBox: Boolean): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            // API 30+ (minSdk 33 la cubre de sobra): exige BIOMETRIC_STRONG específicamente,
            // no cualquier autenticación válida, y con timeout 0 obliga a autenticar en
            // cada operación (`SECURITY.md` §6: BIOMETRIC_STRONG sin
            // DEVICE_CREDENTIAL). Sustituye a la pareja deprecada
            // setUserAuthenticationValidityDurationSeconds(-1) + tipo implícito.
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true) // Invalidar si cambia inscripción
            .setUnlockedDeviceRequired(true)
        if (strongBox) builder.setIsStrongBoxBacked(true)

        keyGen.init(builder.build())
        val key = keyGen.generateKey()
        val expectedLevel = if (strongBox) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        }
        return verifyHardwareLevel(key, expectedLevel)
    }

    private fun verifyHardwareLevel(key: SecretKey, expectedLevel: Int?): SecretKey {
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val actualLevel = keyInfo.securityLevel
        val accepted = if (expectedLevel == null) {
            actualLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                actualLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        } else {
            actualLevel == expectedLevel
        }
        if (!accepted) {
            keyStore.deleteEntry(keyAlias)
            throw java.security.InvalidKeyException("Biometric key is not hardware-backed at the required level")
        }
        lastKeyWasStrongBoxBacked = actualLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        return key
    }

    /** Cipher en modo cifrado, para envolver una `BiometricKEK` recién generada. El IV lo
     * asigna el proveedor del Keystore; hay que leerlo de `cipher.iv` tras autenticar y
     * persistirlo junto al blob, porque hace falta para desenvolver más tarde. */
    fun createEncryptCryptoObject(): BiometricPrompt.CryptoObject? {
        val key = createOrGetBiometricKey()
        return key?.let(::initializeEncryptCryptoObject)
    }

    private fun initializeEncryptCryptoObject(key: SecretKey): BiometricPrompt.CryptoObject? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        BiometricPrompt.CryptoObject(cipher)
    } catch (e: java.security.NoSuchAlgorithmException) {
        logFailure("error creating crypto object", e)
        null
    } catch (e: javax.crypto.NoSuchPaddingException) {
        logFailure("error creating crypto object", e)
        null
    } catch (e: java.security.InvalidKeyException) {
        logFailure("error creating crypto object", e)
        null
    } catch (e: java.security.InvalidAlgorithmParameterException) {
        logFailure("error creating crypto object", e)
        null
    }

    /** Cipher en modo descifrado con el IV guardado al activar la biometría, para
     * recuperar la `BiometricKEK` envuelta. */
    fun createDecryptCryptoObject(iv: ByteArray): BiometricPrompt.CryptoObject? = try {
        val key = createOrGetBiometricKey() ?: return@createDecryptCryptoObject null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        BiometricPrompt.CryptoObject(cipher)
    } catch (e: java.security.NoSuchAlgorithmException) {
        logFailure("error creating crypto object", e)
        null
    } catch (e: javax.crypto.NoSuchPaddingException) {
        logFailure("error creating crypto object", e)
        null
    } catch (e: java.security.InvalidKeyException) {
        logFailure("error creating crypto object", e)
        null
    } catch (e: java.security.InvalidAlgorithmParameterException) {
        logFailure("error creating crypto object", e)
        null
    }

    /** Compatibilidad con la suite instrumentada existente (G-82…G-86): cipher de cifrado
     * sin pasar por `BiometricPrompt.CryptoObject`. */
    fun createCryptoObject(): BiometricPrompt.CryptoObject? = createEncryptCryptoObject()

    /**
     * Invalida la clave biométrica (p.ej., al cambiar inscripción o al desactivar).
     * Borra solo la clave del Keystore; borrar el blob local y el registro de Room es
     * responsabilidad de quien orquesta la desactivación (`VaultRepository`), porque este
     * tipo no conoce Room (`docs/architecture.md` §3).
     */
    fun invalidateBiometricKey(): Boolean = try {
        keyStore.deleteEntry(keyAlias)
        true
    } catch (e: java.security.KeyStoreException) {
        logFailure("error invalidating biometric key", e)
        false
    }

    /**
     * Detecta si la clave se invalidó (p.ej., por cambio de inscripción).
     * Las excepciones se tragan porque es una verificación de estado, no un error.
     */
    @Suppress("SwallowedException")
    fun isBiometricKeyValid(): Boolean = try {
        keyStore.getKey(keyAlias, null) != null
    } catch (e: java.security.KeyStoreException) {
        false
    } catch (e: java.security.NoSuchAlgorithmException) {
        false
    }

    /** Conserva observabilidad operativa sin registrar mensajes, stack traces ni material. */
    private fun logFailure(event: String, failure: Exception) {
        SecureLogger.e("BiometricUnlock", event, "failure_type" to Redact.type(failure))
    }

    companion object {
        const val KEY_ALIAS_BIOMETRIC = "boveda_wilson_biometric_kek_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
    }
}
