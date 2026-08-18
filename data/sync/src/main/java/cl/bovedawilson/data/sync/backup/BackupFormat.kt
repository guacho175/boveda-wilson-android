@file:Suppress(
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "ComplexCondition",
    "ThrowsCount",
    "MagicNumber",
)

package cl.bovedawilson.data.sync.backup

import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.session.UnlockedVault
import cl.bovedawilson.core.crypto.vault.PasswordWrap
import cl.bovedawilson.core.crypto.vault.RecoveryWrap
import cl.bovedawilson.core.crypto.vault.VaultRecord
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.core.crypto.wrap.WrappedVdek
import cl.bovedawilson.data.local.entity.EncryptedItemEntity
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Registro cifrado del formato de respaldo; no contiene modelos de dominio descifrados. */
class BackupItem(
    val itemId: String,
    val ciphertext: ByteArray,
    val cryptoVersion: Int,
    val schemaVersion: Int,
    val revision: Int,
    val tombstone: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Snapshot autocontenido de `vault_meta` y `encrypted_items`, sin ownerUid ni staging local. */
class BackupSnapshot(
    val magic: String,
    val formatVersion: Int,
    val cryptoVersion: Int,
    val schemaVersion: Int,
    val passwordWrapEpoch: Int,
    val recoveryWrapEpoch: Int,
    val vaultId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val metaRevision: Int,
    val kdfName: String,
    val kdfMemoryKib: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    val kdfOutputLen: Int,
    val passwordSalt: ByteArray,
    val recoverySalt: ByteArray,
    val passwordWrappedVdek: ByteArray,
    val recoveryWrappedVdek: ByteArray,
    val manifestAuthenticator: ByteArray,
    val items: List<BackupItem>,
) {
    fun withManifestAuthenticator(authenticator: ByteArray): BackupSnapshot = BackupSnapshot(
        magic = magic,
        formatVersion = formatVersion,
        cryptoVersion = cryptoVersion,
        schemaVersion = schemaVersion,
        passwordWrapEpoch = passwordWrapEpoch,
        recoveryWrapEpoch = recoveryWrapEpoch,
        vaultId = vaultId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        metaRevision = metaRevision,
        kdfName = kdfName,
        kdfMemoryKib = kdfMemoryKib,
        kdfIterations = kdfIterations,
        kdfParallelism = kdfParallelism,
        kdfOutputLen = kdfOutputLen,
        passwordSalt = passwordSalt.copyOf(),
        recoverySalt = recoverySalt.copyOf(),
        passwordWrappedVdek = passwordWrappedVdek.copyOf(),
        recoveryWrappedVdek = recoveryWrappedVdek.copyOf(),
        manifestAuthenticator = authenticator.copyOf(),
        items = items,
    )

    fun toRecord(): VaultRecord = VaultRecord(
        vaultId = vaultId,
        cryptoVersion = CryptoVersion(cryptoVersion),
        schemaVersion = SchemaVersion(schemaVersion),
        password = PasswordWrap(
            parameters = KdfParameters(
                kdfName = kdfName,
                memoryKib = kdfMemoryKib,
                iterations = kdfIterations,
                parallelism = kdfParallelism,
                outputLength = kdfOutputLen,
                salt = passwordSalt.copyOf(),
            ),
            wrappedVdek = WrappedVdek(passwordWrappedVdek.copyOf()),
            epoch = passwordWrapEpoch,
        ),
        recovery = RecoveryWrap(
            salt = recoverySalt.copyOf(),
            wrappedVdek = WrappedVdek(recoveryWrappedVdek.copyOf()),
            epoch = recoveryWrapEpoch,
        ),
    )

    fun toMeta(record: VaultRecord, ownerUid: String): VaultMetaEntity = VaultMetaEntity(
        vaultId = record.vaultId,
        ownerUid = ownerUid,
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
        createdAt = createdAt,
        updatedAt = updatedAt,
        metaRevision = metaRevision,
    )
}

object BackupFormat {
    const val MAGIC = "bw-vault-backup"
    const val FORMAT_VERSION = 2
    const val CRYPTO_VERSION = 1
    const val SCHEMA_VERSION = 1
    const val MAX_ITEMS = 5_000
    const val MAX_CIPHERTEXT_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val EXPORT_FIXED_OVERHEAD_BYTES = 64L * 1024
    private const val EXPORT_ITEM_OVERHEAD_BYTES = 512L
    const val MIN_WRAPPED_VDEK_BYTES = 1
    const val MAX_WRAPPED_VDEK_BYTES = 8 * 1024
    const val MIN_MANIFEST_AUTHENTICATOR_BYTES = 1
    const val MAX_MANIFEST_AUTHENTICATOR_BYTES = 256
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowStructuredMapKeys = false
    }

    fun fromMeta(meta: VaultMetaEntity, items: List<EncryptedItemEntity>): BackupSnapshot {
        if (items.size > MAX_ITEMS) throw BackupFormatException
        return BackupSnapshot(
            magic = MAGIC,
            formatVersion = FORMAT_VERSION,
            cryptoVersion = meta.cryptoVersion,
            schemaVersion = meta.schemaVersion,
            passwordWrapEpoch = meta.passwordWrapEpoch,
            recoveryWrapEpoch = meta.recoveryWrapEpoch,
            vaultId = meta.vaultId,
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt,
            metaRevision = meta.metaRevision,
            kdfName = meta.kdfName,
            kdfMemoryKib = meta.kdfMemoryKib,
            kdfIterations = meta.kdfIterations,
            kdfParallelism = meta.kdfParallelism,
            kdfOutputLen = meta.kdfOutputLen,
            passwordSalt = meta.passwordSalt.copyOf(),
            recoverySalt = meta.recoverySalt.copyOf(),
            passwordWrappedVdek = meta.passwordWrappedVdek.copyOf(),
            recoveryWrappedVdek = meta.recoveryWrappedVdek.copyOf(),
            manifestAuthenticator = byteArrayOf(),
            items = items.map { entity ->
                BackupItem(
                    itemId = entity.itemId,
                    ciphertext = entity.ciphertext.copyOf(),
                    cryptoVersion = entity.cryptoVersion,
                    schemaVersion = entity.schemaVersion,
                    revision = entity.revision,
                    tombstone = entity.tombstone,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                )
            },
        )
    }

    /**
     * Cota conservadora previa a cargar/copiar ciphertext desde Room. Incluye expansión Base64,
     * metadatos JSON por ítem y cabecera; evita construir en memoria un respaldo que será rechazado.
     */
    fun isExportSizeAllowed(itemCount: Long, ciphertextBytes: Long): Boolean {
        if (itemCount !in 0..MAX_ITEMS.toLong() || ciphertextBytes !in 0..MAX_FILE_BYTES) return false
        val base64UpperBound = ((ciphertextBytes + 2L) / 3L) * 4L
        val estimatedBytes = EXPORT_FIXED_OVERHEAD_BYTES +
            itemCount * EXPORT_ITEM_OVERHEAD_BYTES + base64UpperBound
        return estimatedBytes <= MAX_FILE_BYTES
    }

    /** Sella el manifiesto completo con Tink bajo la VDEK. */
    fun authenticate(snapshot: BackupSnapshot, vault: UnlockedVault): BackupSnapshot {
        validateManifest(snapshot)
        val authenticator = vault.authenticateBackupManifest(canonicalManifest(snapshot))
        return snapshot.withManifestAuthenticator(authenticator).also(::validateSnapshot)
    }

    /** Verifica el manifiesto antes de restaurar o autorizar una publicación. */
    fun isAuthentic(snapshot: BackupSnapshot, vault: UnlockedVault): Boolean =
        try {
            validateSnapshot(snapshot)
            vault.verifiesBackupManifest(canonicalManifest(snapshot), snapshot.manifestAuthenticator)
        } catch (_: BackupFormatException) {
            false
        } catch (_: BackupUnsupportedVersionException) {
            false
        }

    fun encode(snapshot: BackupSnapshot): ByteArray {
        validateSnapshot(snapshot)
        val bytes = encodeRoot(buildRoot(snapshot, includeAuthenticator = true))
        if (bytes.size.toLong() > MAX_FILE_BYTES) throw BackupFormatException
        return bytes
    }

    /** Representación JSON determinista de todos los campos salvo el propio autenticador. */
    fun canonicalManifest(snapshot: BackupSnapshot): ByteArray {
        validateManifest(snapshot)
        return encodeRoot(buildRoot(snapshot, includeAuthenticator = false))
    }

    private fun buildRoot(snapshot: BackupSnapshot, includeAuthenticator: Boolean): JsonObject =
        buildJsonObject {
            put("magic", snapshot.magic)
            put("formatVersion", snapshot.formatVersion)
            put("cryptoVersion", snapshot.cryptoVersion)
            put("schemaVersion", snapshot.schemaVersion)
            put("passwordWrapEpoch", snapshot.passwordWrapEpoch)
            put("recoveryWrapEpoch", snapshot.recoveryWrapEpoch)
            put("vaultId", snapshot.vaultId)
            put("createdAt", snapshot.createdAt)
            put("updatedAt", snapshot.updatedAt)
            put("metaRevision", snapshot.metaRevision)
            put(
                "kdf",
                buildJsonObject {
                    put("name", snapshot.kdfName)
                    put("memoryKib", snapshot.kdfMemoryKib)
                    put("iterations", snapshot.kdfIterations)
                    put("parallelism", snapshot.kdfParallelism)
                    put("outputLen", snapshot.kdfOutputLen)
                    put("salt", encodeBytes(snapshot.passwordSalt))
                },
            )
            put(
                "recovery",
                buildJsonObject {
                    put("kdf", "hkdf-sha256")
                    put("outputLen", 32)
                    put("salt", encodeBytes(snapshot.recoverySalt))
                    put("entropyBits", 256)
                    put("words", 24)
                    put("wordlist", "english")
                },
            )
            put("passwordWrappedVdek", encodeBytes(snapshot.passwordWrappedVdek))
            put("recoveryWrappedVdek", encodeBytes(snapshot.recoveryWrappedVdek))
            if (includeAuthenticator) {
                put("manifestAuthenticator", encodeBytes(snapshot.manifestAuthenticator))
            }
            put(
                "items",
                buildJsonArray {
                    snapshot.items.sortedBy(BackupItem::itemId).forEach { item ->
                        add(
                            buildJsonObject {
                                put("itemId", item.itemId)
                                put("ciphertext", encodeBytes(item.ciphertext))
                                put("cryptoVersion", item.cryptoVersion)
                                put("schemaVersion", item.schemaVersion)
                                put("revision", item.revision)
                                put("tombstone", item.tombstone)
                                put("createdAt", item.createdAt)
                                put("updatedAt", item.updatedAt)
                            },
                        )
                    }
                },
            )
        }

    private fun encodeRoot(root: JsonObject): ByteArray =
        json.encodeToString(JsonElement.serializer(), root).toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): BackupSnapshot {
        if (bytes.size.toLong() > MAX_FILE_BYTES) throw BackupFormatException
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw BackupFormatException
        }
        rejectDuplicateObjectKeys(text)
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw BackupFormatException
        }
        return parseRoot(root).also(::validateSnapshot)
    }

    /**
     * `JsonObject` exposes a set of keys, so a permissive decoder would otherwise discard a
     * repeated member before the strict field-set check. Scan object members first and reject
     * duplicate names after JSON string escape decoding, including equivalent escaped names.
     */
    private fun rejectDuplicateObjectKeys(text: String) {
        val scanner = JsonKeyScanner(text)
        scanner.scanDocument()
    }

    private fun parseRoot(element: JsonElement): BackupSnapshot {
        val root = element as? JsonObject ?: throw BackupFormatException
        if (root.stringField("magic") != MAGIC) throw BackupFormatException
        val formatVersion = root.intField("formatVersion")
        if (formatVersion != FORMAT_VERSION) throw BackupUnsupportedVersionException
        if (root.keys != ROOT_FIELDS) throw BackupFormatException
        val kdf = root.objectField("kdf", KDF_FIELDS)
        val recovery = root.objectField("recovery", RECOVERY_FIELDS)
        if (recovery.stringField("kdf") != "hkdf-sha256" || recovery.intField("outputLen") != 32 ||
            recovery.intField("entropyBits") != 256 || recovery.intField("words") != 24 ||
            recovery.stringField("wordlist") != "english"
        ) {
            throw BackupFormatException
        }
        val itemArray = root.arrayField("items")
        if (itemArray.size > MAX_ITEMS) throw BackupFormatException
        val itemIds = HashSet<String>(itemArray.size)
        val items = itemArray.map { raw ->
            val item = raw.objectOrFail(ITEM_FIELDS)
            val itemId = item.stringField("itemId")
            if (!itemIds.add(itemId)) {
                throw BackupFormatException
            }
            BackupItem(
                itemId = itemId,
                ciphertext = decodeBytes(item.stringField("ciphertext"), allowEmpty = true),
                cryptoVersion = item.intField("cryptoVersion"),
                schemaVersion = item.intField("schemaVersion"),
                revision = item.intField("revision"),
                tombstone = item.booleanField("tombstone"),
                createdAt = item.longField("createdAt"),
                updatedAt = item.longField("updatedAt"),
            )
        }
        return BackupSnapshot(
            magic = MAGIC,
            formatVersion = formatVersion,
            cryptoVersion = root.intField("cryptoVersion"),
            schemaVersion = root.intField("schemaVersion"),
            passwordWrapEpoch = root.intField("passwordWrapEpoch"),
            recoveryWrapEpoch = root.intField("recoveryWrapEpoch"),
            vaultId = root.stringField("vaultId"),
            createdAt = root.longField("createdAt"),
            updatedAt = root.longField("updatedAt"),
            metaRevision = root.intField("metaRevision"),
            kdfName = kdf.stringField("name"),
            kdfMemoryKib = kdf.intField("memoryKib"),
            kdfIterations = kdf.intField("iterations"),
            kdfParallelism = kdf.intField("parallelism"),
            kdfOutputLen = kdf.intField("outputLen"),
            passwordSalt = decodeBytes(kdf.stringField("salt"), allowEmpty = false),
            recoverySalt = decodeBytes(recovery.stringField("salt"), allowEmpty = false),
            passwordWrappedVdek = decodeBytes(root.stringField("passwordWrappedVdek"), allowEmpty = false),
            recoveryWrappedVdek = decodeBytes(root.stringField("recoveryWrappedVdek"), allowEmpty = false),
            manifestAuthenticator = decodeBytes(root.stringField("manifestAuthenticator"), allowEmpty = false),
            items = items,
        )
    }

    private fun validateSnapshot(snapshot: BackupSnapshot) {
        validateManifest(snapshot)
        if (snapshot.manifestAuthenticator.size !in
            MIN_MANIFEST_AUTHENTICATOR_BYTES..MAX_MANIFEST_AUTHENTICATOR_BYTES
        ) {
            throw BackupFormatException
        }
    }

    private fun validateManifest(snapshot: BackupSnapshot) {
        if (snapshot.magic != MAGIC) throw BackupFormatException
        if (snapshot.formatVersion != FORMAT_VERSION) throw BackupUnsupportedVersionException
        if (snapshot.cryptoVersion != CRYPTO_VERSION || snapshot.schemaVersion != SCHEMA_VERSION) {
            throw BackupUnsupportedVersionException
        }
        if (!UUID_PATTERN.matches(snapshot.vaultId)) throw BackupFormatException
        if (snapshot.createdAt <= 0 || snapshot.updatedAt < snapshot.createdAt || snapshot.metaRevision < 1) {
            throw BackupFormatException
        }
        if (snapshot.passwordWrapEpoch < 1 || snapshot.recoveryWrapEpoch < 1) throw BackupFormatException
        if (snapshot.kdfName != "argon2id" || snapshot.kdfMemoryKib != 65536 ||
            snapshot.kdfIterations != 3 || snapshot.kdfParallelism != 4 || snapshot.kdfOutputLen != 32 ||
            snapshot.passwordSalt.size != 16 || snapshot.recoverySalt.size != 32 ||
            snapshot.passwordWrappedVdek.size !in MIN_WRAPPED_VDEK_BYTES..MAX_WRAPPED_VDEK_BYTES ||
            snapshot.recoveryWrappedVdek.size !in MIN_WRAPPED_VDEK_BYTES..MAX_WRAPPED_VDEK_BYTES
        ) {
            throw BackupFormatException
        }
        if (snapshot.items.size > MAX_ITEMS) {
            throw BackupFormatException
        }
        val itemIds = HashSet<String>(snapshot.items.size)
        snapshot.items.forEach { item ->
            if (!itemIds.add(item.itemId) || !UUID_PATTERN.matches(item.itemId) ||
                item.cryptoVersion != CRYPTO_VERSION ||
                item.schemaVersion != SCHEMA_VERSION || item.revision < 1 || item.createdAt <= 0 ||
                item.updatedAt < item.createdAt || item.ciphertext.size > MAX_CIPHERTEXT_BYTES ||
                (item.tombstone != item.ciphertext.isEmpty())
            ) {
                throw BackupFormatException
            }
        }
    }

    private fun JsonElement.objectOrFail(expected: Set<String>): JsonObject {
        val objectValue = this as? JsonObject ?: throw BackupFormatException
        if (objectValue.keys != expected) throw BackupFormatException
        return objectValue
    }

    private fun JsonObject.objectField(name: String, expected: Set<String>): JsonObject =
        (this[name] ?: throw BackupFormatException).objectOrFail(expected)

    private fun JsonObject.arrayField(name: String): JsonArray =
        this[name] as? JsonArray ?: throw BackupFormatException

    private fun JsonObject.stringField(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BackupFormatException

    private fun JsonObject.booleanField(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            ?: throw BackupFormatException

    private fun JsonObject.intField(name: String): Int = parseInteger(
        name,
        Int.MIN_VALUE.toLong(),
        Int.MAX_VALUE.toLong(),
    ).toInt()

    private fun JsonObject.longField(name: String): Long = parseInteger(name, Long.MIN_VALUE, Long.MAX_VALUE)

    private fun JsonObject.parseInteger(name: String, min: Long, max: Long): Long {
        val value = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            ?: throw BackupFormatException
        if (!INTEGER_PATTERN.matches(value)) throw BackupFormatException
        return value.toLongOrNull()?.takeIf { it in min..max } ?: throw BackupFormatException
    }

    private fun encodeBytes(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decodeBytes(value: String, allowEmpty: Boolean): ByteArray {
        if (!allowEmpty && value.isEmpty()) throw BackupFormatException
        if (!BASE64URL_PATTERN.matches(value)) throw BackupFormatException
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw BackupFormatException
        }
        if (encodeBytes(decoded) != value) throw BackupFormatException
        return decoded
    }

    private val UUID_PATTERN = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-" +
            "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
    )
    private val INTEGER_PATTERN = Regex("0|[1-9][0-9]*")
    private val BASE64URL_PATTERN = Regex("[A-Za-z0-9_-]*")
    private val ROOT_FIELDS = setOf(
        "magic", "formatVersion", "cryptoVersion", "schemaVersion", "passwordWrapEpoch",
        "recoveryWrapEpoch", "vaultId", "createdAt", "updatedAt", "metaRevision", "kdf",
        "recovery", "passwordWrappedVdek", "recoveryWrappedVdek", "manifestAuthenticator", "items"
    )
    private val KDF_FIELDS = setOf("name", "memoryKib", "iterations", "parallelism", "outputLen", "salt")
    private val RECOVERY_FIELDS = setOf("kdf", "outputLen", "salt", "entropyBits", "words", "wordlist")
    private val ITEM_FIELDS = setOf(
        "itemId",
        "ciphertext",
        "cryptoVersion",
        "schemaVersion",
        "revision",
        "tombstone",
        "createdAt",
        "updatedAt",
    )

    private class JsonKeyScanner(private val text: String) {
        private var index = 0
        private var valueCount = 0

        fun scanDocument() {
            scanValue(depth = 0)
            skipWhitespace()
            if (index != text.length) throw BackupFormatException
        }

        private fun scanValue(depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw BackupFormatException
            valueCount++
            if (valueCount > MAX_JSON_VALUES) throw BackupFormatException
            skipWhitespace()
            if (index >= text.length) throw BackupFormatException
            when (text[index]) {
                '{' -> scanObject(depth + 1)
                '[' -> scanArray(depth + 1)
                '"' -> scanString(decode = false)
                else -> scanPrimitive()
            }
        }

        private fun scanObject(depth: Int) {
            index++
            skipWhitespace()
            val keys = HashSet<String>()
            var memberCount = 0
            if (consume('}')) return
            while (true) {
                memberCount++
                if (memberCount > MAX_JSON_OBJECT_MEMBERS) throw BackupFormatException
                skipWhitespace()
                if (index >= text.length || text[index] != '"') throw BackupFormatException
                val key = scanString(decode = true) ?: throw BackupFormatException
                if (!keys.add(key)) throw BackupFormatException
                skipWhitespace()
                if (!consume(':')) throw BackupFormatException
                scanValue(depth)
                skipWhitespace()
                when {
                    consume('}') -> return
                    consume(',') -> Unit
                    else -> throw BackupFormatException
                }
            }
        }

        private fun scanArray(depth: Int) {
            index++
            skipWhitespace()
            var elementCount = 0
            if (consume(']')) return
            while (true) {
                elementCount++
                if (elementCount > MAX_JSON_ARRAY_ELEMENTS) throw BackupFormatException
                scanValue(depth)
                skipWhitespace()
                when {
                    consume(']') -> return
                    consume(',') -> Unit
                    else -> throw BackupFormatException
                }
            }
        }

        private fun scanPrimitive() {
            val start = index
            while (index < text.length && text[index] !in " \\t\\r\\n,]}") index++
            if (start == index) throw BackupFormatException
        }

        // El escáner debe cubrir todos los escapes JSON en una sola pasada sin delegar en un
        // parser permisivo; la anidación de los casos de escape es intencional.
        @Suppress("NestedBlockDepth")
        private fun scanString(decode: Boolean): String? {
            if (!consume('"')) throw BackupFormatException
            val decoded = if (decode) StringBuilder() else null
            while (index < text.length) {
                when (val character = text[index++]) {
                    '"' -> return decoded?.toString()
                    '\\' -> {
                        if (index >= text.length) throw BackupFormatException
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> decoded?.append(escaped)
                            'b' -> decoded?.append('\b')
                            'f' -> decoded?.append('\u000C')
                            'n' -> decoded?.append('\n')
                            'r' -> decoded?.append('\r')
                            't' -> decoded?.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw BackupFormatException
                                val hex = text.substring(index, index + 4)
                                if (!hex.all { it in "0123456789abcdefABCDEF" }) {
                                    throw BackupFormatException
                                }
                                decoded?.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw BackupFormatException
                        }
                    }
                    else -> {
                        if (character < ' ') throw BackupFormatException
                        decoded?.append(character)
                    }
                }
            }
            throw BackupFormatException
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in " \\t\\r\\n") index++
        }

        private fun consume(expected: Char): Boolean {
            if (index < text.length && text[index] == expected) {
                index++
                return true
            }
            return false
        }
    }

    private const val MAX_JSON_DEPTH = 128
    private const val MAX_JSON_OBJECT_MEMBERS = 16
    private const val MAX_JSON_ARRAY_ELEMENTS = MAX_ITEMS

    // El formato válido máximo usa 45.028 valores (5.000 objetos de 8 campos más cabeceras).
    private const val MAX_JSON_VALUES = 50_000
}

data object BackupFormatException : IOException()
data object BackupUnsupportedVersionException : IOException()
