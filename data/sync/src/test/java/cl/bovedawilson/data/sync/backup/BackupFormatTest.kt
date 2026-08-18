package cl.bovedawilson.data.sync.backup

import cl.bovedawilson.core.common.result.AppResult
import cl.bovedawilson.core.crypto.vault.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class BackupFormatTest {
    @Test
    fun `round trip conserva solo el contrato cifrado`() {
        val original = snapshot()

        val decoded = BackupFormat.decode(BackupFormat.encode(original))

        assertEquals(original.vaultId, decoded.vaultId)
        assertEquals(original.items.single().itemId, decoded.items.single().itemId)
        assertArrayEquals(original.items.single().ciphertext, decoded.items.single().ciphertext)
        assertArrayEquals(original.passwordSalt, decoded.passwordSalt)
        assertArrayEquals(original.recoverySalt, decoded.recoverySalt)
    }

    @Test
    fun `autenticador Tink cubre conjunto estado metadata y ciphertext completos`() {
        val created = requireSuccess(
            VaultCrypto.createVault(
                "123e4567-e89b-42d3-a456-426614174001",
                "FIXTURE-PASSWORD".toCharArray(),
            ),
        )
        val sealed = BackupFormat.authenticate(snapshot(), created.vault)

        assertTrue(BackupFormat.isAuthentic(sealed, created.vault))
        val sameAuthenticator = sealed.manifestAuthenticator
        assertFalse(
            BackupFormat.isAuthentic(
                snapshot(items = emptyList(), authenticator = sameAuthenticator),
                created.vault,
            ),
        )
        assertFalse(
            BackupFormat.isAuthentic(
                snapshot(
                    items = listOf(item(ciphertext = byteArrayOf(), tombstone = true)),
                    authenticator = sameAuthenticator,
                ),
                created.vault,
            ),
        )
        assertFalse(
            BackupFormat.isAuthentic(
                snapshot(items = listOf(item(revision = 2)), authenticator = sameAuthenticator),
                created.vault,
            ),
        )
        assertFalse(
            BackupFormat.isAuthentic(
                snapshot(
                    items = listOf(item(ciphertext = byteArrayOf(9, 8, 7))),
                    authenticator = sameAuthenticator,
                ),
                created.vault,
            ),
        )
    }

    @Test
    fun `rechaza campo desconocido y metadata de recuperacion alterada`() {
        val encoded = BackupFormat.encode(snapshot()).toString(Charsets.UTF_8)
        val withUnknown = encoded.replace("{\"magic\"", "{\"unknown\":1,\"magic\"")
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(withUnknown.toByteArray(Charsets.UTF_8))
        }

        val wrongRecovery = encoded.replace("\"words\":24", "\"words\":23")
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(wrongRecovery.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `rechaza claves JSON duplicadas incluso si la ultima conserva el valor`() {
        val encoded = BackupFormat.encode(snapshot()).toString(Charsets.UTF_8)
        val duplicated = encoded.replace(
            "{\"magic\"",
            "{\"magic\":\"bw-vault-backup\",\"magic\"",
        )

        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(duplicated.toByteArray(Charsets.UTF_8))
        }

        val escapedDuplicate = encoded.replace(
            "{\"magic\"",
            "{\"ma\\u0067ic\":\"bw-vault-backup\",\"magic\"",
        )
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(escapedDuplicate.toByteArray(Charsets.UTF_8))
        }
    }

    @Test(timeout = 5_000)
    fun `fuzzing determinista rechaza entradas malformadas sin excepciones inesperadas`() {
        val seed = 0x42575641554C5431L
        val random = Random(seed)
        val valid = BackupFormat.encode(snapshot())
        repeat(256) { iteration ->
            val candidate = when (iteration % 5) {
                0 -> ByteArray(random.nextInt(0, 1_024)) { random.nextInt(0, 256).toByte() }
                1 -> valid.copyOf(random.nextInt(0, valid.size))
                2 -> (valid + byteArrayOf(0x00)).copyOf()
                3 -> ("[".repeat(256) + valid.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                else -> valid.toString(Charsets.UTF_8).replace("\"items\":[", "\"items\":{")
                    .toByteArray(Charsets.UTF_8)
            }

            try {
                BackupFormat.decode(candidate)
                fail("seed=$seed iteration=$iteration accepted malformed input")
            } catch (error: AssertionError) {
                throw error
            } catch (_: BackupFormatException) {
                // Expected rejection.
            } catch (_: BackupUnsupportedVersionException) {
                // Expected rejection if a random mutation reaches version validation.
            } catch (error: OutOfMemoryError) {
                fail("seed=$seed iteration=$iteration produced OutOfMemoryError: ${error::class.simpleName}")
            } catch (error: Throwable) {
                fail("seed=$seed iteration=$iteration produced ${error::class.simpleName}")
            }
        }
    }

    @Test
    fun `rechaza tombstone inconsistente y ciphertext demasiado grande`() {
        val tombstoneWithData = snapshot(items = listOf(item(tombstone = true, ciphertext = byteArrayOf(1))))
        assertThrows(BackupFormatException::class.java) { BackupFormat.encode(tombstoneWithData) }

        val oversized = snapshot(
            items = listOf(item(ciphertext = ByteArray(BackupFormat.MAX_CIPHERTEXT_BYTES + 1))),
        )
        assertThrows(BackupFormatException::class.java) { BackupFormat.encode(oversized) }

        assertTrue(
            BackupFormat.encode(
                snapshot(items = listOf(item(ciphertext = ByteArray(BackupFormat.MAX_CIPHERTEXT_BYTES)))),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `rechaza mas de 5000 items antes de serializar`() {
        val item = snapshot().items.single()
        val tooMany = snapshot(items = List(BackupFormat.MAX_ITEMS + 1) { item })

        assertThrows(BackupFormatException::class.java) { BackupFormat.encode(tooMany) }
    }

    @Test
    fun `rechaza magic versiones y tipos no soportados`() {
        val valid = encodedSnapshot()
        assertMalformed(valid.replace("\"magic\":\"bw-vault-backup\"", "\"magic\":\"otro\""))
        assertUnsupported(valid.replace("\"formatVersion\":2", "\"formatVersion\":1"))
        assertUnsupported(valid.replace("\"formatVersion\":2", "\"formatVersion\":3"))
        assertMalformed(valid.replace("\"formatVersion\":2", "\"formatVersion\":\"2\""))
        assertUnsupported(valid.replace("\"cryptoVersion\":1", "\"cryptoVersion\":2"))
        assertUnsupported(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
    }

    @Test
    fun `rechaza cada desviacion del perfil KDF v1`() {
        val valid = encodedSnapshot()
        listOf(
            "\"name\":\"argon2id\"" to "\"name\":\"argon2i\"",
            "\"memoryKib\":65536" to "\"memoryKib\":65535",
            "\"iterations\":3" to "\"iterations\":2",
            "\"parallelism\":4" to "\"parallelism\":3",
            "\"outputLen\":32" to "\"outputLen\":31",
        ).forEach { (expected, invalid) ->
            assertMalformed(valid.replaceFirst(expected, invalid))
        }
    }

    @Test
    fun `rechaza ids duplicados activos vacios y base64 no canonico`() {
        val firstId = "123e4567-e89b-42d3-a456-426614174000"
        val secondId = "123e4567-e89b-42d3-a456-426614174002"
        val twoItems = encodedSnapshot(items = listOf(item(firstId), item(secondId)))
        assertMalformed(twoItems.replace(secondId, firstId))

        val valid = encodedSnapshot()
        assertMalformed(valid.replace("\"ciphertext\":\"BwgJ\"", "\"ciphertext\":\"\""))
        assertMalformed(valid.replace("\"ciphertext\":\"BwgJ\"", "\"ciphertext\":\"***\""))
        assertMalformed(valid.replace("\"ciphertext\":\"BwgJ\"", "\"ciphertext\":\"BwgJ=\""))
    }

    @Test
    fun `rechaza epochs salts y numeros fuera de rango`() {
        val valid = encodedSnapshot()
        assertMalformed(valid.replace("\"passwordWrapEpoch\":1", "\"passwordWrapEpoch\":0"))
        assertMalformed(valid.replace("\"recoveryWrapEpoch\":1", "\"recoveryWrapEpoch\":0"))
        assertMalformed(valid.replace("\"salt\":\"AQEBAQEBAQEBAQEBAQEBAQ\"", "\"salt\":\"AQ\""))
        assertMalformed(
            valid.replace(
                "\"salt\":\"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\"",
                "\"salt\":\"Ag\"",
            ),
        )
        assertMalformed(valid.replace("\"revision\":1", "\"revision\":2147483648"))
        assertMalformed(valid.replace("\"createdAt\":1", "\"createdAt\":9223372036854775808"))
    }

    @Test
    fun `acepta limites exactos y rechaza wrappers autenticador timestamps y campos en mas uno`() {
        val exact = snapshot(
            createdAt = Long.MAX_VALUE,
            updatedAt = Long.MAX_VALUE,
            passwordWrappedVdek = ByteArray(BackupFormat.MAX_WRAPPED_VDEK_BYTES),
            recoveryWrappedVdek = ByteArray(BackupFormat.MAX_WRAPPED_VDEK_BYTES),
            authenticator = ByteArray(BackupFormat.MAX_MANIFEST_AUTHENTICATOR_BYTES),
        )
        assertTrue(BackupFormat.encode(exact).isNotEmpty())

        assertThrows(BackupFormatException::class.java) {
            BackupFormat.encode(snapshot(passwordWrappedVdek = ByteArray(BackupFormat.MAX_WRAPPED_VDEK_BYTES + 1)))
        }
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.encode(snapshot(recoveryWrappedVdek = ByteArray(BackupFormat.MAX_WRAPPED_VDEK_BYTES + 1)))
        }
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.encode(snapshot(authenticator = ByteArray(BackupFormat.MAX_MANIFEST_AUTHENTICATOR_BYTES + 1)))
        }

        val valid = encodedSnapshot()
        assertMalformed(valid.replaceFirst("\"metaRevision\":1,", ""))
        assertMalformed(valid.replaceFirst("{\"magic\"", "{\"extra\":1,\"magic\""))
        assertMalformed(valid.replace("\"updatedAt\":1", "\"updatedAt\":9223372036854775808"))
    }

    @Test
    fun `rechaza JSON truncado y archivo por encima del limite seguro`() {
        val valid = BackupFormat.encode(snapshot())
        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(valid.copyOf(valid.size - 1)) }
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(ByteArray((BackupFormat.MAX_FILE_BYTES + 1).toInt()))
        }
    }

    @Test
    fun `rechaza amplificacion por elementos antes de construir el arbol JSON`() {
        val hostile = buildString {
            append('[')
            repeat(BackupFormat.MAX_ITEMS + 1) { index ->
                if (index > 0) append(',')
                append('0')
            }
            append(']')
        }.toByteArray(Charsets.UTF_8)

        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(hostile) }
    }

    @Test
    fun `rechaza amplificacion global por valores anidados antes del parser DOM`() {
        val hostile = buildString {
            append('[')
            repeat(BackupFormat.MAX_ITEMS) { outer ->
                if (outer > 0) append(',')
                append("[0,0,0,0,0,0,0,0,0,0]")
            }
            append(']')
        }.toByteArray(Charsets.UTF_8)

        assertThrows(BackupFormatException::class.java) { BackupFormat.decode(hostile) }
    }

    @Test
    fun `preflight de exportacion rechaza expansion Base64 excesiva sin asignar el contenido`() {
        assertTrue(BackupFormat.isExportSizeAllowed(itemCount = 1, ciphertextBytes = 1024))
        assertFalse(
            BackupFormat.isExportSizeAllowed(
                itemCount = BackupFormat.MAX_ITEMS.toLong(),
                ciphertextBytes = BackupFormat.MAX_ITEMS.toLong() * BackupFormat.MAX_CIPHERTEXT_BYTES,
            ),
        )
    }

    private fun encodedSnapshot(items: List<BackupItem> = listOf(item())): String =
        BackupFormat.encode(snapshot(items = items)).toString(Charsets.UTF_8)

    private fun assertMalformed(value: String) {
        assertThrows(BackupFormatException::class.java) {
            BackupFormat.decode(value.toByteArray(Charsets.UTF_8))
        }
    }

    private fun assertUnsupported(value: String) {
        assertThrows(BackupUnsupportedVersionException::class.java) {
            BackupFormat.decode(value.toByteArray(Charsets.UTF_8))
        }
    }

    private fun item(
        itemId: String = "123e4567-e89b-42d3-a456-426614174000",
        ciphertext: ByteArray = byteArrayOf(7, 8, 9),
        tombstone: Boolean = false,
        revision: Int = 1,
    ) = BackupItem(
        itemId = itemId,
        ciphertext = ciphertext,
        cryptoVersion = 1,
        schemaVersion = 1,
        revision = revision,
        tombstone = tombstone,
        createdAt = 1,
        updatedAt = 1,
    )

    @Suppress("LongParameterList") // Fixture explícita para probar cada límite independiente del formato.
    private fun snapshot(
        items: List<BackupItem> = listOf(item()),
        createdAt: Long = 1,
        updatedAt: Long = 1,
        passwordWrappedVdek: ByteArray = byteArrayOf(3, 4),
        recoveryWrappedVdek: ByteArray = byteArrayOf(5, 6),
        authenticator: ByteArray = ByteArray(33) { 6 },
    ) = BackupSnapshot(
        magic = BackupFormat.MAGIC,
        formatVersion = BackupFormat.FORMAT_VERSION,
        cryptoVersion = 1,
        schemaVersion = 1,
        passwordWrapEpoch = 1,
        recoveryWrapEpoch = 1,
        vaultId = "123e4567-e89b-42d3-a456-426614174001",
        createdAt = createdAt,
        updatedAt = updatedAt,
        metaRevision = 1,
        kdfName = "argon2id",
        kdfMemoryKib = 65536,
        kdfIterations = 3,
        kdfParallelism = 4,
        kdfOutputLen = 32,
        passwordSalt = ByteArray(16) { 1 },
        recoverySalt = ByteArray(32) { 2 },
        passwordWrappedVdek = passwordWrappedVdek,
        recoveryWrappedVdek = recoveryWrappedVdek,
        manifestAuthenticator = authenticator,
        items = items,
    )
}

private fun <T> requireSuccess(result: AppResult<T, *>): T = when (result) {
    is AppResult.Success -> result.value
    is AppResult.Failure -> error("fixture setup failed")
}
