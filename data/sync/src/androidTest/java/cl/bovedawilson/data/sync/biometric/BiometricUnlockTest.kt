package cl.bovedawilson.data.sync.biometric

import android.content.Context
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Pruebas instrumentadas de BiometricUnlock contra Android Keystore real.
 *
 * Verifica:
 * - Clave no exportable
 * - Autenticación de usuario requerida
 * - Intento de StrongBox (con fallback a TEE)
 * - Invalidación segura
 */
@RunWith(AndroidJUnit4::class)
class BiometricUnlockTest {
    private lateinit var context: Context
    private lateinit var biometricUnlock: BiometricUnlock

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        biometricUnlock = BiometricUnlock(context, "boveda_wilson_biometric_test_${UUID.randomUUID()}")
        assumeTrue("El dispositivo debe tener BIOMETRIC_STRONG inscrita", biometricUnlock.canAuthenticate())
    }

    @After
    fun tearDown() {
        runBlocking { biometricUnlock.invalidateBiometricKey() }
    }

    // G-82: Clave creada con atributos correctos
    @Test
    fun testBiometricKeyCreation() = runBlocking {
        val key = biometricUnlock.createOrGetBiometricKey()
        assertNotNull("Clave biométrica debe crearse", key)
        assertTrue("Clave debe ser SecretKey", key is SecretKey)
    }

    // G-83: Clave no es exportable
    @Test
    fun testBiometricKeyIsNotExportable() = runBlocking {
        val key = requireNotNull(biometricUnlock.createOrGetBiometricKey() as? SecretKey)
        assertTrue("Clave debe ser AES", key.algorithm == "AES")
        assertTrue("Clave debe ser no exportable", key.encoded == null)

        val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertTrue("Debe exigir autenticación", keyInfo.booleanProperty("isUserAuthenticationRequired"))
        assertTrue("Debe tener 256 bits", keyInfo.intProperty("getKeySize") == 256)
        assertTrue(
            "Debe permitir únicamente biometría fuerte",
            keyInfo.intProperty("getUserAuthenticationType") == KeyProperties.AUTH_BIOMETRIC_STRONG
        )
        assertTrue(
            "Debe vivir en TEE o StrongBox",
            keyInfo.intProperty("getSecurityLevel") == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                keyInfo.intProperty("getSecurityLevel") == KeyProperties.SECURITY_LEVEL_STRONGBOX
        )
    }

    // G-84: Validez de la clave
    @Test
    fun testBiometricKeyValidity() = runBlocking {
        val key = biometricUnlock.createOrGetBiometricKey()
        assertTrue("Clave debe ser válida después de creación", biometricUnlock.isBiometricKeyValid())
    }

    // G-85: Invalidación borra la clave
    @Test
    fun testBiometricKeyInvalidation() = runBlocking {
        // Crear la clave
        biometricUnlock.createOrGetBiometricKey()
        assertTrue("Clave debe ser válida inicialmente", biometricUnlock.isBiometricKeyValid())

        // Invalidar
        val success = biometricUnlock.invalidateBiometricKey()
        assertTrue("Invalidación debe tener éxito", success)
        assertFalse("Clave no debe ser válida después de invalidación", biometricUnlock.isBiometricKeyValid())
    }

    // G-86: CryptoObject se puede crear
    @Test
    fun testCryptoObjectCreation() = runBlocking {
        val cryptoObject = biometricUnlock.createCryptoObject()
        assertNotNull("CryptoObject debe crearse", cryptoObject)
    }
}

private fun KeyInfo.booleanProperty(method: String): Boolean =
    KeyInfo::class.java.getMethod(method).invoke(this) as Boolean

private fun KeyInfo.intProperty(method: String): Int =
    KeyInfo::class.java.getMethod(method).invoke(this) as Int
