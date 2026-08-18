package cl.bovedawilson.data.remote.firestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDocumentParserTest {

    @Test
    fun `acepta metadata v1 completa dentro de limites`() {
        val parsed = RemoteDocumentParser.vault(FIXTURE_VAULT_ID, validVaultFields(), NOW_MS)

        assertEquals(FIXTURE_VAULT_ID, parsed.id)
        assertEquals(1, parsed.metadata.metaRevision)
        assertEquals(16, parsed.metadata.passwordSalt.size)
    }

    @Test
    fun `rechaza campo ausente extra y tipo incorrecto`() {
        val missing = validVaultFields().toMutableMap().apply { remove("recoveryWrappedVdek") }
        val extra = validVaultFields().toMutableMap().apply { put("unexpected", 1L) }
        val wrongType = validVaultFields().toMutableMap().apply { put("metaRevision", "1") }

        expectMalformed { RemoteDocumentParser.vault(FIXTURE_VAULT_ID, missing, NOW_MS) }
        expectMalformed { RemoteDocumentParser.vault(FIXTURE_VAULT_ID, extra, NOW_MS) }
        expectMalformed { RemoteDocumentParser.vault(FIXTURE_VAULT_ID, wrongType, NOW_MS) }
    }

    @Test
    fun `rechaza overflow al convertir enteros de Firestore`() {
        val overflow = validVaultFields().toMutableMap().apply {
            put("schemaVersion", Int.MAX_VALUE.toLong() + 1L)
        }

        expectMalformed { RemoteDocumentParser.vault(FIXTURE_VAULT_ID, overflow, NOW_MS) }
    }

    @Test
    fun `rechaza versiones perfil KDF salts y envoltorios invalidos`() {
        val mutations = listOf<Pair<String, Any?>>(
            "schemaVersion" to 2L,
            "cryptoVersion" to 2L,
            "kdfName" to "unexpected",
            "kdfMemoryKib" to 1L,
            "kdfIterations" to 2L,
            "kdfParallelism" to 1L,
            "kdfOutputLen" to 16L,
            "passwordSalt" to ByteArray(15),
            "recoverySalt" to ByteArray(31),
            "passwordWrappedVdek" to ByteArray(0),
            "recoveryWrappedVdek" to ByteArray(MAX_WRAPPED_VDEK_BYTES + 1)
        )

        mutations.forEach { (field, value) ->
            val fields = validVaultFields().toMutableMap().apply { put(field, value) }
            expectMalformed { RemoteDocumentParser.vault(FIXTURE_VAULT_ID, fields, NOW_MS) }
        }
    }

    @Test
    fun `rechaza epochs revision e intervalos temporales incoherentes`() {
        val invalidFields = listOf(
            "passwordWrapEpoch" to 0L,
            "recoveryWrapEpoch" to 0L,
            "metaRevision" to 0L,
            "createdAt" to 0L,
            "updatedAt" to (CREATED_AT_MS - 1L),
            "updatedAt" to (NOW_MS + 300_001L)
        )

        invalidFields.forEach { (field, value) ->
            val fields = validVaultFields().toMutableMap().apply { put(field, value) }
            expectMalformed { RemoteDocumentParser.vault(FIXTURE_VAULT_ID, fields, NOW_MS) }
        }
    }

    @Test
    fun `item exige v1 revision valida y coherencia tombstone ciphertext`() {
        val valid = RemoteDocumentParser.item(FIXTURE_ITEM_ID, validItemFields(), NOW_MS)
        assertTrue(valid.ciphertext.isNotEmpty())

        val invalid = listOf(
            "schemaVersion" to 2L,
            "cryptoVersion" to 2L,
            "revision" to 0L,
            "revision" to (Int.MAX_VALUE.toLong() + 1L),
            "ciphertext" to ByteArray(MAX_REMOTE_CIPHERTEXT_BYTES + 1),
            "tombstone" to true
        )
        invalid.forEach { (field, value) ->
            val fields = validItemFields().toMutableMap().apply { put(field, value) }
            expectMalformed { RemoteDocumentParser.item(FIXTURE_ITEM_ID, fields, NOW_MS) }
        }
    }

    private fun validVaultFields(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1L,
        "cryptoVersion" to 1L,
        "kdfName" to "argon2id",
        "kdfMemoryKib" to 65_536L,
        "kdfIterations" to 3L,
        "kdfParallelism" to 4L,
        "kdfOutputLen" to 32L,
        "passwordSalt" to ByteArray(16) { it.toByte() }, // fixture ficticio
        "passwordWrappedVdek" to ByteArray(48) { 1 }, // fixture ficticio
        "recoverySalt" to ByteArray(32) { it.toByte() }, // fixture ficticio
        "recoveryWrappedVdek" to ByteArray(48) { 2 }, // fixture ficticio
        "passwordWrapEpoch" to 1L,
        "recoveryWrapEpoch" to 1L,
        "createdAt" to CREATED_AT_MS,
        "updatedAt" to CREATED_AT_MS,
        "metaRevision" to 1L
    )

    private fun validItemFields(): Map<String, Any?> = mapOf(
        "ciphertext" to ByteArray(48) { 3 }, // fixture ficticio, no es ciphertext real
        "cryptoVersion" to 1L,
        "schemaVersion" to 1L,
        "revision" to 1L,
        "tombstone" to false,
        "createdAt" to CREATED_AT_MS,
        "updatedAt" to CREATED_AT_MS
    )

    private fun expectMalformed(block: () -> Unit) {
        val result = runCatching(block)
        assertTrue(result.exceptionOrNull() is MalformedRemoteDataException)
    }

    private companion object {
        const val FIXTURE_VAULT_ID = "11111111-1111-4111-8111-111111111111"
        const val FIXTURE_ITEM_ID = "22222222-2222-4222-8222-222222222222"
        const val CREATED_AT_MS = 1_000L
        const val NOW_MS = 2_000L
    }
}
