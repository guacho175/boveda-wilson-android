package cl.bovedawilson.core.crypto.aead

import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** G-47/G-48: la AAD se reconstruye byte a byte y cualquier campo distinto produce otra AAD. */
class AadBuilderTest {

    private fun params(salt: ByteArray = ByteArray(16) { it.toByte() }) =
        KdfParameters("argon2id", 65536, 3, 4, 32, salt)

    @Test
    fun `forItem es identica byte a byte para los mismos metadatos`() {
        val a = AadBuilder.forItem("vault-1", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        val b = AadBuilder.forItem("vault-1", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        assertArrayEquals(a.bytes, b.bytes)
    }

    @Test
    fun `forItem cambia si cambia el vaultId`() {
        val a = AadBuilder.forItem("vault-1", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        val b = AadBuilder.forItem("vault-2", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `forItem cambia si cambia el itemId`() {
        val a = AadBuilder.forItem("vault-1", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        val b = AadBuilder.forItem("vault-1", "item-2", SchemaVersion.V1, CryptoVersion.V1)
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `forItem cambia si cambia la version de esquema`() {
        val a = AadBuilder.forItem("vault-1", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        val b = AadBuilder.forItem("vault-1", "item-1", SchemaVersion(2), CryptoVersion.V1)
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `identificador con separador prohibido lanza MalformedInput`() {
        assertThrows(CryptoError.MalformedInput::class.java) {
            AadBuilder.forItem("vault|1", "item-1", SchemaVersion.V1, CryptoVersion.V1)
        }
    }

    @Test
    fun `forPasswordWrap es identica byte a byte para los mismos parametros`() {
        val p = params()
        val a = AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, p, 1)
        val b = AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, p, 1)
        assertArrayEquals(a.bytes, b.bytes)
    }

    @Test
    fun `forPasswordWrap cambia si cambia el epoch`() {
        val p = params()
        val a = AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, p, 1)
        val b = AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, p, 2)
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `forPasswordWrap cambia si cambia cualquier parametro del KDF`() {
        val base = AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, params(), 1)
        val memoriaDistinta = AadBuilder.forPasswordWrap(
            "vault-1",
            CryptoVersion.V1,
            KdfParameters("argon2id", 1024, 3, 4, 32, ByteArray(16) { it.toByte() }),
            1,
        )
        assertFalse(base.bytes.contentEquals(memoriaDistinta.bytes))
    }

    @Test
    fun `forPasswordWrap rechaza un kdfName distinto de argon2id`() {
        val ajeno = KdfParameters("argon2i", 65536, 3, 4, 32, ByteArray(16))
        assertThrows(CryptoError.MalformedInput::class.java) {
            AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, ajeno, 1)
        }
    }

    @Test
    fun `forRecoveryWrap es identica byte a byte y distinta si cambia el salt`() {
        val salt = ByteArray(32) { it.toByte() }
        val otroSalt = ByteArray(32) { (it + 1).toByte() }
        val a = AadBuilder.forRecoveryWrap("vault-1", CryptoVersion.V1, salt, 1)
        val b = AadBuilder.forRecoveryWrap("vault-1", CryptoVersion.V1, salt, 1)
        val c = AadBuilder.forRecoveryWrap("vault-1", CryptoVersion.V1, otroSalt, 1)
        assertArrayEquals(a.bytes, b.bytes)
        assertFalse(a.bytes.contentEquals(c.bytes))
    }

    @Test
    fun `forRecoveryWrap cambia si cambia el epoch`() {
        val salt = ByteArray(32) { it.toByte() }
        val a = AadBuilder.forRecoveryWrap("vault-1", CryptoVersion.V1, salt, 1)
        val b = AadBuilder.forRecoveryWrap("vault-1", CryptoVersion.V1, salt, 2)
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `forBiometricKek es identica byte a byte y distinta si cambia el alias`() {
        val a = AadBuilder.forBiometricKek("vault-1", "bw.biometric.vdek.v1", CryptoVersion.V1)
        val b = AadBuilder.forBiometricKek("vault-1", "bw.biometric.vdek.v1", CryptoVersion.V1)
        val c = AadBuilder.forBiometricKek("vault-1", "bw.biometric.vdek.v2", CryptoVersion.V1)
        assertArrayEquals(a.bytes, b.bytes)
        assertFalse(a.bytes.contentEquals(c.bytes))
    }

    @Test
    fun `un epoch negativo lanza MalformedInput`() {
        assertThrows(CryptoError.MalformedInput::class.java) {
            AadBuilder.forPasswordWrap("vault-1", CryptoVersion.V1, params(), -1)
        }
    }
}
