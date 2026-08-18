package cl.bovedawilson.core.crypto.vault

import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.item.ItemField
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.recovery.Bip39Codec
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matriz de pruebas de `CRYPTOGRAPHY.md` para las operaciones de alto
 * nivel: creación, desbloqueo, cambio de contraseña y regeneración de la recuperación.
 */
class VaultCryptoTest {

    // FIXTURE_PASSWORD / FIXTURE_OTHER_PASSWORD: valores ficticios, no usar en producción.
    private val fixturePassword = "correcto-caballo-batería-grapa-ficticia".toCharArray()
    private val fixtureOtherPassword = "otra-contraseña-completamente-distinta".toCharArray()

    private fun <T> AppResult<T, CryptoError>.orFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AssertionError("se esperaba éxito, falló con $error", error)
    }

    private fun <T> AppResult<T, CryptoError>.errorOrFail(): CryptoError = when (this) {
        is AppResult.Success -> throw AssertionError("se esperaba un fallo y hubo éxito")
        is AppResult.Failure -> error
    }

    private fun itemAad(vaultId: String) = AadBuilder.forItem(vaultId, "item-1", SchemaVersion.V1, CryptoVersion.V1)

    private fun samplePayload() = ItemPayload(
        v = 1,
        title = "Nota ficticia",
        body = "contenido ficticio, no es un secreto real",
        tags = emptyList(),
        fields = listOf(ItemField("campo", "valor-ficticio", secret = true)),
        createdAt = 0,
        updatedAt = 0,
    )

    private fun createFictitiousVault(
        vaultId: String = "vault-1",
        password: CharArray = fixturePassword.copyOf(),
    ): CreatedVault = VaultCrypto.createVault(vaultId, password).orFail()

    private fun withPasswordEpoch(record: VaultRecord, epoch: Int) = VaultRecord(
        vaultId = record.vaultId,
        cryptoVersion = record.cryptoVersion,
        schemaVersion = record.schemaVersion,
        password = PasswordWrap(record.password.parameters, record.password.wrappedVdek, epoch),
        recovery = record.recovery,
    )

    private fun withRecoveryEpoch(record: VaultRecord, epoch: Int) = VaultRecord(
        vaultId = record.vaultId,
        cryptoVersion = record.cryptoVersion,
        schemaVersion = record.schemaVersion,
        password = record.password,
        recovery = RecoveryWrap(record.recovery.salt, record.recovery.wrappedVdek, epoch),
    )

    @Test
    fun `crear la boveda permite cifrar y descifrar de inmediato`() {
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))
        assertEquals(samplePayload(), created.vault.decrypt(ciphertext, itemAad(created.record.vaultId)))
    }

    @Test
    fun `createVault inicia ambos epochs en 1`() {
        val created = createFictitiousVault()
        assertEquals(1, created.record.password.epoch)
        assertEquals(1, created.record.recovery.epoch)
    }

    @Test
    fun `createVault entrega una frase de 24 palabras`() {
        val created = createFictitiousVault()
        assertEquals(24, created.recoveryPhrase.wordCount)
    }

    @Test
    fun `unlockWithPassword con la contrasena correcta reabre la misma VDEK`() {
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))

        val reopened = VaultCrypto.unlockWithPassword(created.record, fixturePassword.copyOf()).orFail()

        assertEquals(samplePayload(), reopened.decrypt(ciphertext, itemAad(created.record.vaultId)))
    }

    @Test
    fun `unlockWithPassword con contrasena incorrecta falla con InvalidCredentials`() {
        val created = createFictitiousVault()
        val result = VaultCrypto.unlockWithPassword(created.record, fixtureOtherPassword.copyOf())
        assertEquals(CryptoError.InvalidCredentials, result.errorOrFail())
    }

    @Test
    fun `unlockWithRecovery abre la misma VDEK que la contrasena`() {
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))

        val reopened = VaultCrypto.unlockWithRecovery(created.record, created.recoveryPhrase.toWordList()).orFail()

        assertEquals(samplePayload(), reopened.decrypt(ciphertext, itemAad(created.record.vaultId)))
    }

    @Test
    fun `restaurar con contrasena reemite ambos envoltorios`() {
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))

        val restored = VaultCrypto.restoreWithPassword(created.record, fixturePassword.copyOf()).orFail()

        assertEquals(2, restored.record.password.epoch)
        assertEquals(2, restored.record.recovery.epoch)
        assertEquals(24, restored.recoveryPhrase?.wordCount)
        assertEquals(
            samplePayload(),
            VaultCrypto.unlockWithPassword(restored.record, fixturePassword.copyOf())
                .orFail()
                .decrypt(ciphertext, itemAad(created.record.vaultId))
        )
        assertEquals(
            samplePayload(),
            VaultCrypto.unlockWithRecovery(restored.record, restored.recoveryPhrase!!.toWordList())
                .orFail()
                .decrypt(ciphertext, itemAad(created.record.vaultId))
        )
    }

    @Test
    fun `restaurar con frase reemite contrasena y ambos envoltorios`() {
        val created = createFictitiousVault()
        val newPassword = fixtureOtherPassword.copyOf()

        val restored = VaultCrypto.restoreWithRecovery(
            created.record,
            created.recoveryPhrase.toWordList(),
            newPassword,
        ).orFail()

        assertEquals(null, restored.recoveryPhrase)
        assertEquals(2, restored.record.password.epoch)
        assertEquals(2, restored.record.recovery.epoch)
        assertTrue(VaultCrypto.unlockWithPassword(restored.record, fixtureOtherPassword.copyOf()) is AppResult.Success)
        assertTrue(
            VaultCrypto.unlockWithRecovery(restored.record, created.recoveryPhrase.toWordList()) is AppResult.Success
        )
    }

    @Test
    fun `restaurar supera las marcas de epoch locales`() {
        val created = createFictitiousVault()

        val restored = VaultCrypto.restoreWithPassword(
            created.record,
            fixturePassword.copyOf(),
            passwordEpochFloor = 7,
            recoveryEpochFloor = 11,
        ).orFail()

        assertEquals(8, restored.record.password.epoch)
        assertEquals(12, restored.record.recovery.epoch)
    }

    @Test
    fun `G-52 usar la frase de recuperacion no la invalida, sigue abriendo el envoltorio actual`() {
        val created = createFictitiousVault()
        val words = created.recoveryPhrase.toWordList()

        VaultCrypto.unlockWithRecovery(created.record, words).orFail()
        val second = VaultCrypto.unlockWithRecovery(created.record, words).orFail()

        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))
        assertEquals(samplePayload(), second.decrypt(ciphertext, itemAad(created.record.vaultId)))
    }

    @Test
    fun `una palabra incorrecta en la recuperacion falla con InvalidCredentials`() {
        val created = createFictitiousVault()
        val words = created.recoveryPhrase.toWordList().toMutableList()
        words[0] = Bip39Codec.wordList().first { it != words[0] }

        val result = VaultCrypto.unlockWithRecovery(created.record, words)
        assertEquals(CryptoError.InvalidCredentials, result.errorOrFail())
    }

    @Test
    fun `cryptoVersion desconocida falla con UnsupportedVersion`() {
        val created = createFictitiousVault()
        val record = created.record
        val futureVersionRecord = VaultRecord(
            vaultId = record.vaultId,
            cryptoVersion = CryptoVersion(2),
            schemaVersion = record.schemaVersion,
            password = record.password,
            recovery = record.recovery,
        )

        val result = VaultCrypto.unlockWithPassword(futureVersionRecord, fixturePassword.copyOf())
        assertEquals(CryptoError.UnsupportedVersion, result.errorOrFail())
    }

    @Test
    fun `downgrade de los parametros del KDF en los metadatos hace fallar el unwrap`() {
        val created = createFictitiousVault()
        val record = created.record
        val downgradedParameters = KdfParameters(
            record.password.parameters.kdfName,
            1024,
            record.password.parameters.iterations,
            record.password.parameters.parallelism,
            record.password.parameters.outputLength,
            record.password.parameters.salt,
        )
        val downgraded = VaultRecord(
            vaultId = record.vaultId,
            cryptoVersion = record.cryptoVersion,
            schemaVersion = record.schemaVersion,
            password = PasswordWrap(downgradedParameters, record.password.wrappedVdek, record.password.epoch),
            recovery = record.recovery,
        )

        val result = VaultCrypto.unlockWithPassword(downgraded, fixturePassword.copyOf())
        assertEquals(CryptoError.WeakParameters, result.errorOrFail())
    }

    @Test
    fun `cambiar la contrasena maestra mantiene las notas legibles y no invalida la recuperacion`() {
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))
        val newPassword = "contraseña-nueva-ficticia-distinta".toCharArray()

        val newRecord = VaultCrypto.changeMasterPassword(
            created.record,
            fixturePassword.copyOf(),
            newPassword.copyOf(),
        ).orFail()

        assertEquals(2, newRecord.password.epoch)
        assertEquals(created.record.recovery.epoch, newRecord.recovery.epoch)
        assertTrue(created.record.recovery.wrappedVdek.bytes.contentEquals(newRecord.recovery.wrappedVdek.bytes))

        val withNewPassword = VaultCrypto.unlockWithPassword(newRecord, newPassword.copyOf()).orFail()
        assertEquals(samplePayload(), withNewPassword.decrypt(ciphertext, itemAad(newRecord.vaultId)))

        val withOldPassword = VaultCrypto.unlockWithPassword(newRecord, fixturePassword.copyOf())
        assertEquals(CryptoError.InvalidCredentials, withOldPassword.errorOrFail())

        val withOriginalRecovery = VaultCrypto.unlockWithRecovery(
            newRecord,
            created.recoveryPhrase.toWordList(),
        ).orFail()
        assertEquals(samplePayload(), withOriginalRecovery.decrypt(ciphertext, itemAad(newRecord.vaultId)))
    }

    @Test
    fun `regenerar la frase entrega una nueva que abre la misma VDEK y mantiene las notas`() {
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))

        val regenerated = VaultCrypto.regenerateRecovery(created.record, fixturePassword.copyOf()).orFail()

        assertEquals(2, regenerated.record.recovery.epoch)
        assertEquals(created.record.password.epoch, regenerated.record.password.epoch)
        assertNotEquals(created.recoveryPhrase.toWordList(), regenerated.recoveryPhrase.toWordList())

        val reopened = VaultCrypto.unlockWithRecovery(
            regenerated.record,
            regenerated.recoveryPhrase.toWordList(),
        ).orFail()
        assertEquals(samplePayload(), reopened.decrypt(ciphertext, itemAad(regenerated.record.vaultId)))

        val stillOpensWithPassword = VaultCrypto.unlockWithPassword(
            regenerated.record,
            fixturePassword.copyOf(),
        ).orFail()
        assertEquals(samplePayload(), stillOpensWithPassword.decrypt(ciphertext, itemAad(regenerated.record.vaultId)))
    }

    @Test
    fun `la frase anterior no abre el envoltorio de recuperacion vigente tras regenerar`() {
        val created = createFictitiousVault()
        val regenerated = VaultCrypto.regenerateRecovery(created.record, fixturePassword.copyOf()).orFail()

        val result = VaultCrypto.unlockWithRecovery(regenerated.record, created.recoveryPhrase.toWordList())
        assertEquals(CryptoError.InvalidCredentials, result.errorOrFail())
    }

    @Test
    fun `regenerar no revoca una copia antigua del envoltorio que conserve la frase anterior`() {
        // CRYPTOGRAPHY.md §12 y §15: la regeneración reemplaza lo que se persiste desde ahora,
        // pero no revoca criptográficamente una copia anterior ya obtenida junto a su frase.
        val created = createFictitiousVault()
        val ciphertext = created.vault.encrypt(samplePayload(), itemAad(created.record.vaultId))
        VaultCrypto.regenerateRecovery(created.record, fixturePassword.copyOf()).orFail()

        val reopenedFromOldCopy = VaultCrypto.unlockWithRecovery(
            created.record,
            created.recoveryPhrase.toWordList(),
        ).orFail()
        assertEquals(samplePayload(), reopenedFromOldCopy.decrypt(ciphertext, itemAad(created.record.vaultId)))
    }

    @Test
    fun `un epoch regresivo emparejado con un envoltorio mas nuevo falla`() {
        val created = createFictitiousVault()
        val newPassword = "contraseña-nueva-ficticia-distinta".toCharArray()
        val afterChange = VaultCrypto.changeMasterPassword(
            created.record,
            fixturePassword.copyOf(),
            newPassword.copyOf(),
        ).orFail()

        // Un servidor hostil sirve el envoltorio nuevo (epoch 2) junto a metadatos con epoch 1.
        val tampered = withPasswordEpoch(afterChange, 1)

        val result = VaultCrypto.unlockWithPassword(tampered, newPassword.copyOf())
        assertEquals(CryptoError.InvalidCredentials, result.errorOrFail())
    }

    @Test
    fun `G-55 un epoch de recuperacion regresivo emparejado con un envoltorio mas nuevo falla`() {
        val created = createFictitiousVault()
        val afterRegen = VaultCrypto.regenerateRecovery(created.record, fixturePassword.copyOf()).orFail()

        // Un servidor hostil sirve el envoltorio de recuperación nuevo (epoch 2) junto a
        // metadatos con epoch 1: el mismo ataque que para la contraseña, del lado contrario.
        val tampered = withRecoveryEpoch(afterRegen.record, 1)

        val result = VaultCrypto.unlockWithRecovery(tampered, afterRegen.recoveryPhrase.toWordList())
        assertEquals(CryptoError.InvalidCredentials, result.errorOrFail())
    }

    @Test
    fun `G-54 verifySameVdek rechaza dos VDEK distintas antes de persistir`() {
        val vaultA = UnlockedVault.withNewVdek()
        val vaultB = UnlockedVault.withNewVdek()

        assertThrows(CryptoError.InternalError::class.java) {
            VaultWrapping.verifySameVdek(vaultA.handleForWrapping(), vaultB.handleForWrapping())
        }
    }

    @Test
    fun `la misma contrasena en dos bovedas produce envoltorios distintos por el salt aleatorio`() {
        val created = createFictitiousVault()
        val other = createFictitiousVault(vaultId = "vault-2", password = fixturePassword.copyOf())
        assertFalse(created.record.password.wrappedVdek.bytes.contentEquals(other.record.password.wrappedVdek.bytes))
    }

    @Test
    fun `los mensajes de error no contienen la contrasena ni las palabras de recuperacion`() {
        val created = createFictitiousVault()
        val secretPassword = String(fixturePassword)
        val secretWord = created.recoveryPhrase.toWordList().first()

        val passwordFailure = VaultCrypto.unlockWithPassword(
            created.record,
            fixtureOtherPassword.copyOf(),
        ).errorOrFail()
        assertFalse(passwordFailure.message.orEmpty().contains(secretPassword))
        assertFalse(passwordFailure.toString().contains(secretPassword))

        val tamperedWords = created.recoveryPhrase.toWordList().toMutableList()
        tamperedWords[1] = Bip39Codec.wordList().first { it != tamperedWords[1] }
        val recoveryFailure = VaultCrypto.unlockWithRecovery(created.record, tamperedWords).errorOrFail()
        assertFalse(recoveryFailure.message.orEmpty().contains(secretWord))
        assertFalse(recoveryFailure.toString().contains(secretWord))
    }
}
