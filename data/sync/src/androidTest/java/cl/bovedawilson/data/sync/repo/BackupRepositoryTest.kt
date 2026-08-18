package cl.bovedawilson.data.sync.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cl.bovedawilson.core.common.AppDispatchers
import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.aead.AadBuilder
import cl.bovedawilson.core.crypto.error.CryptoError
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.core.crypto.vault.CreatedVault
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import cl.bovedawilson.core.crypto.vault.VaultRecord
import cl.bovedawilson.data.local.db.VaultDatabase
import cl.bovedawilson.data.local.entity.BiometricUnlockEntity
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import cl.bovedawilson.data.local.store.RoomVaultMetaStore
import cl.bovedawilson.data.sync.backup.BackupFormat
import cl.bovedawilson.data.sync.backup.BackupFormatException
import cl.bovedawilson.data.sync.session.SessionState
import cl.bovedawilson.data.sync.session.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private lateinit var database: VaultDatabase
    private lateinit var repository: BackupRepository
    private lateinit var session: VaultSession
    private lateinit var created: CreatedVault
    private lateinit var originalMeta: VaultMetaEntity
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE_NAME)
        databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        database = Room.databaseBuilder(context, VaultDatabase::class.java, TEST_DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        session = VaultSession()
        created = requireSuccess(VaultCrypto.createVault(VAULT_ID, FIXTURE_PASSWORD.copyOf()))
        originalMeta = metaFor(created.record)
        database.vaultMetaDao().insertOrUpdate(originalMeta)
        database.encryptedItemDao().insertOrReplace(fixtureItem(ITEM_ID, FIXTURE_CIPHERTEXT))
        repository = BackupRepository(
            database = database,
            metaDao = database.vaultMetaDao(),
            metaStore = RoomVaultMetaStore(database.vaultMetaDao()),
            itemDao = database.encryptedItemDao(),
            dispatchers = AppDispatchers(),
            session = session,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun exportIsCiphertextOnlyAndRequiresUnlockedSession() = runBlocking {
        val lockedOutput = RecordingOutputStream()
        var lockedOpenCalls = 0
        val lockedResult = repository.exportVault(
            { lockedOpenCalls++; lockedOutput },
            FIXTURE_PASSWORD.copyOf(),
        )
        assertTrue(lockedResult is AppResult.Failure)
        assertEquals(0, lockedOpenCalls)
        assertEquals(0, lockedOutput.writeCount)

        unlockSession()
        val output = RecordingOutputStream()
        val result = repository.exportVault({ output }, FIXTURE_PASSWORD.copyOf())

        assertTrue(result is AppResult.Success)
        assertTrue(output.bytes.toString(Charsets.UTF_8).contains("ciphertext"))
        assertFalse(output.bytes.toString(Charsets.UTF_8).contains("BW-CANARY-BACKUP-DO-NOT-PERSIST"))
        val decoded = BackupFormat.decode(output.bytes)
        assertArrayEquals(FIXTURE_CIPHERTEXT, decoded.items.single().ciphertext)
    }

    @Test
    fun exportRejectsMoreThanFiveThousandItemsBeforeWriting() = runBlocking {
        unlockSession()
        database.encryptedItemDao().deleteAll()
        database.encryptedItemDao().insertOrReplaceAll(
            List(BackupFormat.MAX_ITEMS + 1) { index -> fixtureItem("fixture-item-$index", byteArrayOf(1, 2, 3)) },
        )
        val output = RecordingOutputStream()
        var openCalls = 0

        val result = repository.exportVault({ openCalls++; output }, FIXTURE_PASSWORD.copyOf())

        assertTrue(result is AppResult.Failure)
        assertEquals(0, openCalls)
        assertEquals(0, output.writeCount)
        assertEquals(BackupFormat.MAX_ITEMS + 1, database.encryptedItemDao().getAllItems().size)
    }

    @Test
    fun exportRejectsOversizedCiphertextBeforeOpeningDestination() = runBlocking {
        unlockSession()
        database.encryptedItemDao().deleteAll()
        database.encryptedItemDao().insertOrReplaceAll(
            List(25) { index ->
                fixtureItem(
                    "123e4567-e89b-42d3-a456-${index.toString().padStart(12, '0')}",
                    ByteArray(BackupFormat.MAX_CIPHERTEXT_BYTES) { 7 },
                )
            },
        )
        val output = RecordingOutputStream()
        var openCalls = 0

        val result = repository.exportVault({ openCalls++; output }, FIXTURE_PASSWORD.copyOf())

        assertTrue(result is AppResult.Failure)
        assertEquals(0, openCalls)
        assertEquals(0, output.writeCount)
    }

    @Test
    fun exportSqlPreflightAcceptsExactCiphertextLimitAndRejectsPlusOne() = runBlocking {
        unlockSession()
        database.encryptedItemDao().deleteAll()
        database.encryptedItemDao().insertOrReplace(
            fixtureItem(ITEM_ID, ByteArray(BackupFormat.MAX_CIPHERTEXT_BYTES) { 7 }),
        )
        val exactOutput = RecordingOutputStream()

        assertTrue(repository.exportVault({ exactOutput }, FIXTURE_PASSWORD.copyOf()) is AppResult.Success)

        database.encryptedItemDao().insertOrReplace(
            fixtureItem(ITEM_ID, ByteArray(BackupFormat.MAX_CIPHERTEXT_BYTES + 1) { 7 }),
        )
        var plusOneOpenCalls = 0
        val rejected = repository.exportVault(
            { plusOneOpenCalls++; RecordingOutputStream() },
            FIXTURE_PASSWORD.copyOf(),
        )
        assertTrue(rejected is AppResult.Failure)
        assertEquals(0, plusOneOpenCalls)
        assertEquals(
            (BackupFormat.MAX_CIPHERTEXT_BYTES + 1).toLong(),
            database.encryptedItemDao().getBackupSizeStats().maxCiphertextBytes,
        )
    }

    @Test
    fun exportExplicitlyExcludesBiometricUnlockEntity() = runBlocking {
        val biometricCanary = "FIXTURE-BIOMETRIC-LOCAL-ONLY".toByteArray(Charsets.UTF_8)
        database.biometricUnlockDao().insertOrUpdate(
            BiometricUnlockEntity(
                id = 1,
                keyAlias = "fixture-biometric-local-only",
                wrappedBiometricKek = biometricCanary.copyOf(),
                biometricWrappedVdek = biometricCanary.copyOf(),
                biometricWrapEpoch = 1,
                iv = biometricCanary.copyOf(),
                strongBoxBacked = false,
                createdAt = 1,
            ),
        )
        unlockSession()
        val backup = exportBytes()

        assertFalse(backup.contains(biometricCanary))
        assertFalse(backup.toString(Charsets.UTF_8).contains("fixture-biometric-local-only"))
        assertTrue(database.biometricUnlockDao().get() != null)
    }

    @Test
    fun importRejectsCompactTokenAmplificationNearFileLimitWithoutDomOom() {
        val hostile = ByteArray((BackupFormat.MAX_FILE_BYTES - 1).toInt()) { ' '.code.toByte() }
        val prefix = buildString {
            append('[')
            repeat(BackupFormat.MAX_ITEMS + 1) { index ->
                if (index > 0) append(',')
                append('0')
            }
        }.toByteArray(Charsets.UTF_8)
        prefix.copyInto(hostile)
        hostile[hostile.lastIndex] = ']'.code.toByte()

        var rejected = false
        try {
            BackupFormat.decode(hostile)
        } catch (_: BackupFormatException) {
            rejected = true
        }
        assertTrue("El respaldo hostil debe rechazarse antes de construir el DOM", rejected)
    }

    @Test
    fun exportRejectsIncorrectReauthenticationBeforeWriting() = runBlocking {
        unlockSession()
        val output = RecordingOutputStream()
        var openCalls = 0

        val result = repository.exportVault({ openCalls++; output }, WRONG_PASSWORD.copyOf())

        assertTrue(result is AppResult.Failure)
        assertEquals(0, openCalls)
        assertEquals(0, output.writeCount)
        assertEquals(originalMeta, database.vaultMetaDao().getMeta())
    }

    @Test
    fun restoreRollsBackRoomAndKeepsSessionLockedWhenTransactionFails() = runBlocking {
        unlockSession()
        val backup = exportBytes()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_backup_restore
            BEFORE INSERT ON encrypted_items
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END
            """.trimIndent(),
        )

        val result = repository.restoreWithPassword(
            ByteArrayInputStream(backup),
            FIXTURE_PASSWORD.copyOf(),
        )

        assertTrue(result is AppResult.Failure)
        assertEquals(SessionState.Locked, session.state.value)
        assertEquals(originalMeta, database.vaultMetaDao().getMeta())
        assertArrayEquals(
            FIXTURE_CIPHERTEXT,
            requireNotNull(database.encryptedItemDao().getById(ITEM_ID)).ciphertext,
        )
    }

    @Test
    fun successfulRestoreReemitsAndLocksSessionBeforeReturning() = runBlocking {
        unlockSession()
        val backup = exportBytes()
        database.encryptedItemDao().deleteAll()

        val result = repository.restoreWithPassword(
            ByteArrayInputStream(backup),
            FIXTURE_PASSWORD.copyOf(),
        )

        assertTrue(result is AppResult.Success)
        assertEquals(SessionState.Locked, session.state.value)
        assertArrayEquals(
            FIXTURE_CIPHERTEXT,
            requireNotNull(database.encryptedItemDao().getById(ITEM_ID)).ciphertext,
        )
        val restoredMeta = requireNotNull(database.vaultMetaDao().getMeta())
        assertTrue(restoredMeta.passwordWrapEpoch > originalMeta.passwordWrapEpoch)
        assertTrue(restoredMeta.recoveryWrapEpoch > originalMeta.recoveryWrapEpoch)
    }

    @Test
    fun differentVaultRestoreWithoutStrongConfirmationChangesNothing() = runBlocking {
        unlockSession()
        val other = requireSuccess(VaultCrypto.createVault(OTHER_VAULT_ID, OTHER_PASSWORD.copyOf()))
        val backup = BackupFormat.encode(
            BackupFormat.authenticate(
                BackupFormat.fromMeta(
                metaFor(other.record),
                listOf(fixtureItem(OTHER_ITEM_ID, byteArrayOf(7, 7, 7))),
                ),
                other.vault,
            ),
        )
        val beforeMeta = requireNotNull(database.vaultMetaDao().getMeta())
        val beforeItems = database.encryptedItemDao().getAllItems()

        val result = repository.restoreWithPassword(ByteArrayInputStream(backup), OTHER_PASSWORD.copyOf())

        assertTrue(result is AppResult.Failure)
        assertEquals(beforeMeta, database.vaultMetaDao().getMeta())
        assertEquals(beforeItems, database.encryptedItemDao().getAllItems())
        assertTrue(session.state.value is SessionState.Unlocked)
    }

    @Test
    fun restoreWithRecoveryReemitsBothWrappersAndLocksSession() = runBlocking {
        val recoveryWords = created.recoveryPhrase.toWordList()
        unlockSession()
        val backup = exportBytes()
        database.encryptedItemDao().deleteAll()

        val result = repository.restoreWithRecovery(
            ByteArrayInputStream(backup),
            recoveryWords,
            NEW_PASSWORD.copyOf(),
        )

        assertTrue(result is AppResult.Success)
        assertEquals(null, (result as AppResult.Success).value.recoveryPhrase)
        assertEquals(SessionState.Locked, session.state.value)
        assertArrayEquals(
            FIXTURE_CIPHERTEXT,
            requireNotNull(database.encryptedItemDao().getById(ITEM_ID)).ciphertext,
        )
        val restoredMeta = requireNotNull(database.vaultMetaDao().getMeta())
        assertTrue(restoredMeta.passwordWrapEpoch > originalMeta.passwordWrapEpoch)
        assertTrue(restoredMeta.recoveryWrapEpoch > originalMeta.recoveryWrapEpoch)
    }

    @Test
    fun roomFilesAndExportDoNotContainPlaintextCanary() = runBlocking {
        val ciphertext = created.vault.encrypt(
            ItemPayload(
                v = 1,
                title = PLAINTEXT_CANARY,
                body = "fixture body $PLAINTEXT_CANARY",
                tags = listOf("fixture"),
                fields = emptyList(),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            AadBuilder.forItem(VAULT_ID, CANARY_ITEM_ID, SchemaVersion.V1, CryptoVersion.V1),
        ).bytes
        database.encryptedItemDao().insertOrReplace(fixtureItem(CANARY_ITEM_ID, ciphertext))
        unlockSession()
        val output = RecordingOutputStream()

        assertTrue(repository.exportVault({ output }, FIXTURE_PASSWORD.copyOf()) is AppResult.Success)
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()

        val canaryBytes = PLAINTEXT_CANARY.toByteArray(Charsets.UTF_8)
        val persistedFiles = listOf(
            databaseFile,
            File("${databaseFile.path}-wal"),
            File("${databaseFile.path}-shm"),
        ).filter(File::exists)
        assertTrue("Expected a physical Room database", persistedFiles.isNotEmpty())
        persistedFiles.forEach { file ->
            assertFalse("Plaintext canary found in ${file.name}", file.readBytes().contains(canaryBytes))
        }
        assertFalse("Plaintext canary found in backup", output.bytes.contains(canaryBytes))
    }

    private suspend fun exportBytes(): ByteArray {
        val output = RecordingOutputStream()
        val result = repository.exportVault({ output }, FIXTURE_PASSWORD.copyOf())
        check(result is AppResult.Success)
        return output.bytes
    }

    private fun unlockSession() {
        val lease = requireNotNull(session.beginUnlock())
        check(session.tryUnlock(lease, created.vault, VAULT_ID))
    }

    private fun metaFor(record: VaultRecord) = VaultMetaEntity(
        vaultId = record.vaultId,
        ownerUid = "fixture-owner",
        schemaVersion = record.schemaVersion.value,
        cryptoVersion = record.cryptoVersion.value,
        kdfName = record.password.parameters.kdfName,
        kdfMemoryKib = record.password.parameters.memoryKib,
        kdfIterations = record.password.parameters.iterations,
        kdfParallelism = record.password.parameters.parallelism,
        kdfOutputLen = record.password.parameters.outputLength,
        passwordSalt = record.password.parameters.salt.copyOf(),
        passwordWrappedVdek = record.password.wrappedVdek.bytes.copyOf(),
        recoverySalt = record.recovery.salt.copyOf(),
        recoveryWrappedVdek = record.recovery.wrappedVdek.bytes.copyOf(),
        passwordWrapEpoch = record.password.epoch,
        recoveryWrapEpoch = record.recovery.epoch,
        createdAt = 1L,
        updatedAt = 1L,
        metaRevision = 1,
    )

    private fun fixtureItem(itemId: String, ciphertext: ByteArray) = EncryptedItemEntity(
        itemId = itemId,
        ciphertext = ciphertext.copyOf(),
        cryptoVersion = 1,
        schemaVersion = 1,
        revision = 1,
        tombstone = false,
        createdAt = 1L,
        updatedAt = 1L,
        dirty = false,
        lastSyncedRevision = 1,
        conflictOf = null,
        pendingRemoteCiphertext = null,
        pendingRemoteRevision = null,
        pendingRemoteCryptoVersion = null,
        pendingRemoteSchemaVersion = null,
        pendingRemoteTombstone = null,
        pendingRemoteCreatedAt = null,
        pendingRemoteUpdatedAt = null,
    )

    private class RecordingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        var writeCount: Int = 0
            private set

        val bytes: ByteArray get() = delegate.toByteArray()

        override fun write(byte: Int) {
            writeCount++
            delegate.write(byte)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writeCount++
            delegate.write(bytes, offset, length)
        }
    }

    private companion object {
        private const val TEST_DATABASE_NAME = "backup-repository-test.db"
        private const val VAULT_ID = "123e4567-e89b-42d3-a456-426614174001"
        private const val ITEM_ID = "123e4567-e89b-42d3-a456-426614174000"
        private const val CANARY_ITEM_ID = "123e4567-e89b-42d3-a456-426614174002"
        private const val OTHER_VAULT_ID = "123e4567-e89b-42d3-a456-426614174003"
        private const val OTHER_ITEM_ID = "123e4567-e89b-42d3-a456-426614174004"
        private const val PLAINTEXT_CANARY = "BW-ROOM-CANARY-PLAINTEXT-DO-NOT-PERSIST"

        // Valores ficticios, no usar en producción.
        private val FIXTURE_PASSWORD = charArrayOf('F', 'I', 'X', 'T', 'U', 'R', 'E', '-', 'P', 'A', 'S', 'S')
        private val WRONG_PASSWORD = charArrayOf('W', 'R', 'O', 'N', 'G', '-', 'P', 'A', 'S', 'S')
        private val NEW_PASSWORD = charArrayOf('N', 'E', 'W', '-', 'F', 'I', 'X', 'T', 'U', 'R', 'E')
        private val OTHER_PASSWORD = charArrayOf('O', 'T', 'H', 'E', 'R', '-', 'F', 'I', 'X', 'T', 'U', 'R', 'E')
        private val FIXTURE_CIPHERTEXT = byteArrayOf(11, 22, 33, 44, 55, 66)
    }
}

private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    return indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }
}

private fun <T> requireSuccess(result: AppResult<T, CryptoError>): T = when (result) {
    is AppResult.Success -> result.value
    is AppResult.Failure -> error("fixture setup failed")
}
