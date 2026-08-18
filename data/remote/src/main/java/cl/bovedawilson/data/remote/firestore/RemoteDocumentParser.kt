@file:Suppress("TooManyFunctions")

package cl.bovedawilson.data.remote.firestore

import cl.bovedawilson.core.crypto.kdf.KdfPolicy
import java.util.UUID

private const val CURRENT_SCHEMA_VERSION = 1
private const val CURRENT_CRYPTO_VERSION = 1
private const val RECOVERY_SALT_BYTES = 32
internal const val MAX_WRAPPED_VDEK_BYTES = 8_192
internal const val MAX_REMOTE_CIPHERTEXT_BYTES = 262_144
private const val MAX_CLOCK_SKEW_MS = 300_000L

private val VAULT_FIELDS = setOf(
    "schemaVersion",
    "cryptoVersion",
    "kdfName",
    "kdfMemoryKib",
    "kdfIterations",
    "kdfParallelism",
    "kdfOutputLen",
    "passwordSalt",
    "passwordWrappedVdek",
    "recoverySalt",
    "recoveryWrappedVdek",
    "passwordWrapEpoch",
    "recoveryWrapEpoch",
    "createdAt",
    "updatedAt",
    "metaRevision"
)

private val ITEM_FIELDS = setOf(
    "ciphertext",
    "cryptoVersion",
    "schemaVersion",
    "revision",
    "tombstone",
    "createdAt",
    "updatedAt"
)

/** Categoría fija: nunca incorpora valores remotos ni la causa original. */
class MalformedRemoteDataException : IllegalArgumentException("malformed_remote_data")

/**
 * Parser puro de los mapas recibidos de Firestore. Rechaza campos ausentes, extra,
 * de tipo incorrecto y enteros que no quepan exactamente en `Int`.
 */
internal object RemoteDocumentParser {
    fun vault(
        vaultId: String,
        fields: Map<String, Any?>,
        nowMillis: Long = System.currentTimeMillis()
    ): RemoteVaultData {
        requireExactFields(fields, VAULT_FIELDS)
        val data = RemoteVaultData(
            id = vaultId,
            metadata = RemoteVaultMetadata(
                schemaVersion = fields.requiredInt("schemaVersion"),
                cryptoVersion = fields.requiredInt("cryptoVersion"),
                kdfName = fields.requiredString("kdfName"),
                kdfMemoryKib = fields.requiredInt("kdfMemoryKib"),
                kdfIterations = fields.requiredInt("kdfIterations"),
                kdfParallelism = fields.requiredInt("kdfParallelism"),
                kdfOutputLen = fields.requiredInt("kdfOutputLen"),
                passwordSalt = fields.requiredBytes("passwordSalt"),
                passwordWrappedVdek = fields.requiredBytes("passwordWrappedVdek"),
                recoverySalt = fields.requiredBytes("recoverySalt"),
                recoveryWrappedVdek = fields.requiredBytes("recoveryWrappedVdek"),
                passwordWrapEpoch = fields.requiredInt("passwordWrapEpoch"),
                recoveryWrapEpoch = fields.requiredInt("recoveryWrapEpoch"),
                createdAt = fields.requiredLong("createdAt"),
                updatedAt = fields.requiredLong("updatedAt"),
                metaRevision = fields.requiredInt("metaRevision")
            )
        )
        RemoteVaultValidator.requireValid(data, nowMillis)
        return data
    }

    fun item(
        itemId: String,
        fields: Map<String, Any?>,
        nowMillis: Long = System.currentTimeMillis()
    ): RemoteItemData {
        requireExactFields(fields, ITEM_FIELDS)
        val data = RemoteItemData(
            id = itemId,
            ciphertext = fields.requiredBytes("ciphertext"),
            cryptoVersion = fields.requiredInt("cryptoVersion"),
            schemaVersion = fields.requiredInt("schemaVersion"),
            revision = fields.requiredInt("revision"),
            tombstone = fields.requiredBoolean("tombstone"),
            createdAt = fields.requiredLong("createdAt"),
            updatedAt = fields.requiredLong("updatedAt")
        )
        requireValidItem(data, nowMillis)
        return data
    }
}

/** Validación reutilizable en `:data:sync` antes de adoptar y persistir metadata remota. */
object RemoteVaultValidator {
    fun requireValid(data: RemoteVaultData, nowMillis: Long = System.currentTimeMillis()) {
        val metadata = data.metadata
        requireUuid(data.id)
        requireRemote(metadata.schemaVersion == CURRENT_SCHEMA_VERSION)
        requireRemote(metadata.cryptoVersion == CURRENT_CRYPTO_VERSION)
        requireRemote(metadata.kdfName == KdfPolicy.KDF_NAME)
        requireRemote(metadata.kdfMemoryKib == KdfPolicy.MEMORY_KIB)
        requireRemote(metadata.kdfIterations == KdfPolicy.ITERATIONS)
        requireRemote(metadata.kdfParallelism == KdfPolicy.PARALLELISM)
        requireRemote(metadata.kdfOutputLen == KdfPolicy.OUTPUT_LENGTH)
        requireRemote(metadata.passwordSalt.size == KdfPolicy.SALT_LENGTH)
        requireRemote(metadata.recoverySalt.size == RECOVERY_SALT_BYTES)
        requireRemote(metadata.passwordWrappedVdek.size in 1..MAX_WRAPPED_VDEK_BYTES)
        requireRemote(metadata.recoveryWrappedVdek.size in 1..MAX_WRAPPED_VDEK_BYTES)
        requireRemote(metadata.passwordWrapEpoch >= 1)
        requireRemote(metadata.recoveryWrapEpoch >= 1)
        requireRemote(metadata.metaRevision >= 1)
        requireValidDates(metadata.createdAt, metadata.updatedAt, nowMillis)
    }

    fun isValid(data: RemoteVaultData, nowMillis: Long = System.currentTimeMillis()): Boolean = try {
        requireValid(data, nowMillis)
        true
    } catch (_: MalformedRemoteDataException) {
        false
    }

    /**
     * Replica en el cliente el contrato relativo de las Security Rules. Se usa tanto
     * antes de enviar una actualización como antes de adoptar metadata remota.
     */
    fun requireValidTransition(
        vaultId: String,
        current: RemoteVaultMetadata,
        candidate: RemoteVaultMetadata,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        requireValid(RemoteVaultData(vaultId, current), nowMillis)
        requireValid(RemoteVaultData(vaultId, candidate), nowMillis)
        requireRemote(candidate.createdAt == current.createdAt)
        requireRemote(candidate.updatedAt >= current.updatedAt)
        requireRemote(candidate.metaRevision > current.metaRevision)
        requireRemote(candidate.cryptoVersion >= current.cryptoVersion)
        requireRemote(candidate.schemaVersion >= current.schemaVersion)
        requireRemote(candidate.kdfMemoryKib >= current.kdfMemoryKib)
        requireRemote(candidate.kdfIterations >= current.kdfIterations)
        requireRemote(candidate.kdfParallelism >= current.kdfParallelism)
        requireRemote(candidate.kdfOutputLen == current.kdfOutputLen)

        val passwordChanged = !candidate.hasSamePasswordWrap(current)
        requireRemote(
            if (passwordChanged) {
                candidate.passwordWrapEpoch > current.passwordWrapEpoch
            } else {
                candidate.passwordWrapEpoch == current.passwordWrapEpoch
            }
        )
        val recoveryChanged = !candidate.hasSameRecoveryWrap(current)
        requireRemote(
            if (recoveryChanged) {
                candidate.recoveryWrapEpoch > current.recoveryWrapEpoch
            } else {
                candidate.recoveryWrapEpoch == current.recoveryWrapEpoch
            }
        )
    }

    // El contrato cerrado se compara campo por campo para que una ampliación no se acepte
    // accidentalmente por igualdad parcial ni por el equals de arrays.
    @Suppress("CyclomaticComplexMethod")
    fun hasSameContent(first: RemoteVaultMetadata, second: RemoteVaultMetadata): Boolean =
        first.schemaVersion == second.schemaVersion &&
            first.cryptoVersion == second.cryptoVersion &&
            first.kdfName == second.kdfName &&
            first.kdfMemoryKib == second.kdfMemoryKib &&
            first.kdfIterations == second.kdfIterations &&
            first.kdfParallelism == second.kdfParallelism &&
            first.kdfOutputLen == second.kdfOutputLen &&
            first.passwordSalt.contentEquals(second.passwordSalt) &&
            first.passwordWrappedVdek.contentEquals(second.passwordWrappedVdek) &&
            first.recoverySalt.contentEquals(second.recoverySalt) &&
            first.recoveryWrappedVdek.contentEquals(second.recoveryWrappedVdek) &&
            first.passwordWrapEpoch == second.passwordWrapEpoch &&
            first.recoveryWrapEpoch == second.recoveryWrapEpoch &&
            first.createdAt == second.createdAt &&
            first.updatedAt == second.updatedAt &&
            first.metaRevision == second.metaRevision
}

private fun RemoteVaultMetadata.hasSamePasswordWrap(other: RemoteVaultMetadata): Boolean =
    kdfName == other.kdfName &&
        kdfMemoryKib == other.kdfMemoryKib &&
        kdfIterations == other.kdfIterations &&
        kdfParallelism == other.kdfParallelism &&
        kdfOutputLen == other.kdfOutputLen &&
        passwordSalt.contentEquals(other.passwordSalt) &&
        passwordWrappedVdek.contentEquals(other.passwordWrappedVdek)

private fun RemoteVaultMetadata.hasSameRecoveryWrap(other: RemoteVaultMetadata): Boolean =
    recoverySalt.contentEquals(other.recoverySalt) &&
        recoveryWrappedVdek.contentEquals(other.recoveryWrappedVdek)

internal fun requireValidItem(data: RemoteItemData, nowMillis: Long = System.currentTimeMillis()) {
    requireUuid(data.id)
    requireRemote(data.schemaVersion == CURRENT_SCHEMA_VERSION)
    requireRemote(data.cryptoVersion == CURRENT_CRYPTO_VERSION)
    requireRemote(data.revision >= 1)
    requireRemote(data.ciphertext.size <= MAX_REMOTE_CIPHERTEXT_BYTES)
    requireRemote(data.tombstone == data.ciphertext.isEmpty())
    requireValidDates(data.createdAt, data.updatedAt, nowMillis)
}

private fun requireValidDates(createdAt: Long, updatedAt: Long, nowMillis: Long) {
    val latestAllowed = if (nowMillis > Long.MAX_VALUE - MAX_CLOCK_SKEW_MS) {
        Long.MAX_VALUE
    } else {
        nowMillis + MAX_CLOCK_SKEW_MS
    }
    requireRemote(createdAt > 0L)
    requireRemote(updatedAt >= createdAt)
    requireRemote(updatedAt <= latestAllowed)
}

private fun requireUuid(value: String) {
    val canonical = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw MalformedRemoteDataException()
    }
    requireRemote(canonical.equals(value, ignoreCase = true))
}

private fun requireExactFields(fields: Map<String, Any?>, expected: Set<String>) {
    requireRemote(fields.keys == expected)
}

private fun Map<String, Any?>.requiredInt(field: String): Int {
    val value = requiredLong(field)
    requireRemote(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return value.toInt()
}

private fun Map<String, Any?>.requiredLong(field: String): Long =
    this[field] as? Long ?: throw MalformedRemoteDataException()

private fun Map<String, Any?>.requiredString(field: String): String =
    this[field] as? String ?: throw MalformedRemoteDataException()

private fun Map<String, Any?>.requiredBytes(field: String): ByteArray =
    this[field] as? ByteArray ?: throw MalformedRemoteDataException()

private fun Map<String, Any?>.requiredBoolean(field: String): Boolean =
    this[field] as? Boolean ?: throw MalformedRemoteDataException()

private fun requireRemote(condition: Boolean) {
    if (!condition) throw MalformedRemoteDataException()
}
