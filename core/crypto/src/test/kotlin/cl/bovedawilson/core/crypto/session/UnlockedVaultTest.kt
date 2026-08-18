package cl.bovedawilson.core.crypto.session

import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.item.ItemField
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Matriz de la Fase 2, casos 1-4: cifrado de ítems directamente con la VDEK. */
class UnlockedVaultTest {

    private fun payload(title: String = "Nota ficticia") = ItemPayload(
        v = 1,
        title = title,
        body = "Cuerpo ficticio, no es un secreto real",
        tags = listOf("ficticio"),
        fields = listOf(ItemField("usuario", "valor-ficticio", secret = true)),
        createdAt = 0,
        updatedAt = 0,
    )

    private fun aad(vaultId: String = "vault-1", itemId: String = "item-1") =
        AadBuilder.forItem(vaultId, itemId, SchemaVersion.V1, CryptoVersion.V1)

    @Test
    fun `cifra y descifra correctamente con AAD`() {
        val vault = UnlockedVault.withNewVdek()
        val original = payload()
        val ciphertext = vault.encrypt(original, aad())
        val decrypted = vault.decrypt(ciphertext, aad())
        assertEquals(original, decrypted)
    }

    @Test
    fun `dos cifrados del mismo contenido producen ciphertext distinto`() {
        val vault = UnlockedVault.withNewVdek()
        val a = vault.encrypt(payload(), aad())
        val b = vault.encrypt(payload(), aad())
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `AAD incorrecta falla con IntegrityFailure`() {
        val vault = UnlockedVault.withNewVdek()
        val ciphertext = vault.encrypt(payload(), aad(itemId = "item-1"))
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vault.decrypt(ciphertext, aad(itemId = "item-2"))
        }
    }

    @Test
    fun `G-48 trasplantar el ciphertext a otro vaultId falla`() {
        val vault = UnlockedVault.withNewVdek()
        val ciphertext = vault.encrypt(payload(), aad(vaultId = "vault-origen"))
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vault.decrypt(ciphertext, aad(vaultId = "vault-destino"))
        }
    }

    @Test
    fun `cada byte del ciphertext alterado hace fallar el descifrado`() {
        val vault = UnlockedVault.withNewVdek()
        val ciphertext = vault.encrypt(payload(), aad())
        for (i in ciphertext.bytes.indices) {
            val tampered = ciphertext.bytes.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0xFF).toByte()
            val tamperedCiphertext = Ciphertext.fromPersisted(tampered)
            assertThrows("posición $i debería fallar", CryptoError.IntegrityFailure::class.java) {
                vault.decrypt(tamperedCiphertext, aad())
            }
        }
    }

    @Test
    fun `dos bovedas distintas no pueden descifrarse entre si`() {
        val vaultA = UnlockedVault.withNewVdek()
        val vaultB = UnlockedVault.withNewVdek()
        val ciphertext = vaultA.encrypt(payload(), aad())
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vaultB.decrypt(ciphertext, aad())
        }
    }

    @Test
    fun `un ciphertext vacio falla con IntegrityFailure, sin excepcion cruda`() {
        val vault = UnlockedVault.withNewVdek()
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vault.decrypt(Ciphertext.fromPersisted(ByteArray(0)), aad())
        }
    }

    @Test
    fun `un ciphertext de un solo byte falla con IntegrityFailure, sin excepcion cruda`() {
        val vault = UnlockedVault.withNewVdek()
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vault.decrypt(Ciphertext.fromPersisted(byteArrayOf(0)), aad())
        }
    }

    @Test
    fun `un ciphertext truncado a la mitad falla con IntegrityFailure, sin excepcion cruda`() {
        val vault = UnlockedVault.withNewVdek()
        val ciphertext = vault.encrypt(payload(), aad())
        val truncated = ciphertext.bytes.copyOf(ciphertext.bytes.size / 2)
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vault.decrypt(Ciphertext.fromPersisted(truncated), aad())
        }
    }

    @Test
    fun `un ciphertext con bytes extra al final falla con IntegrityFailure, sin excepcion cruda`() {
        val vault = UnlockedVault.withNewVdek()
        val ciphertext = vault.encrypt(payload(), aad())
        val extended = ciphertext.bytes.copyOf(ciphertext.bytes.size + 200)
        assertThrows(CryptoError.IntegrityFailure::class.java) {
            vault.decrypt(Ciphertext.fromPersisted(extended), aad())
        }
    }
}
