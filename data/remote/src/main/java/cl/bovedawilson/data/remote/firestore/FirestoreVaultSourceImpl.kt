package cl.bovedawilson.data.remote.firestore

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.data.remote.internal.EmulatorConfig
import cl.bovedawilson.data.remote.internal.awaitResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.util.UUID

private const val COLLECTION_USERS = "users"
private const val COLLECTION_VAULTS = "vaults"
private const val COLLECTION_ITEMS = "items"
private const val COLLECTION_DELETED_VAULTS = "deletedVaults"

// Nombres de campo del documento de bóveda, lista cerrada de docs/architecture.md §5.
private const val FIELD_SCHEMA_VERSION = "schemaVersion"
private const val FIELD_CRYPTO_VERSION = "cryptoVersion"
private const val FIELD_KDF_NAME = "kdfName"
private const val FIELD_KDF_MEMORY_KIB = "kdfMemoryKib"
private const val FIELD_KDF_ITERATIONS = "kdfIterations"
private const val FIELD_KDF_PARALLELISM = "kdfParallelism"
private const val FIELD_KDF_OUTPUT_LEN = "kdfOutputLen"
private const val FIELD_PASSWORD_SALT = "passwordSalt"
private const val FIELD_PASSWORD_WRAPPED_VDEK = "passwordWrappedVdek"
private const val FIELD_RECOVERY_SALT = "recoverySalt"
private const val FIELD_RECOVERY_WRAPPED_VDEK = "recoveryWrappedVdek"
private const val FIELD_PASSWORD_WRAP_EPOCH = "passwordWrapEpoch"
private const val FIELD_RECOVERY_WRAP_EPOCH = "recoveryWrapEpoch"
private const val FIELD_CREATED_AT = "createdAt"
private const val FIELD_UPDATED_AT = "updatedAt"
private const val FIELD_META_REVISION = "metaRevision"

// Campos adicionales del documento de ítem.
private const val FIELD_CIPHERTEXT = "ciphertext"
private const val FIELD_REVISION = "revision"
private const val FIELD_TOMBSTONE = "tombstone"
private const val FIELD_DELETED_AT = "deletedAt"

private fun RemoteVaultMetadata.toFirestoreMap(): Map<String, Any> = mapOf(
    FIELD_SCHEMA_VERSION to schemaVersion,
    FIELD_CRYPTO_VERSION to cryptoVersion,
    FIELD_KDF_NAME to kdfName,
    FIELD_KDF_MEMORY_KIB to kdfMemoryKib,
    FIELD_KDF_ITERATIONS to kdfIterations,
    FIELD_KDF_PARALLELISM to kdfParallelism,
    FIELD_KDF_OUTPUT_LEN to kdfOutputLen,
    FIELD_PASSWORD_SALT to Blob.fromBytes(passwordSalt),
    FIELD_PASSWORD_WRAPPED_VDEK to Blob.fromBytes(passwordWrappedVdek),
    FIELD_RECOVERY_SALT to Blob.fromBytes(recoverySalt),
    FIELD_RECOVERY_WRAPPED_VDEK to Blob.fromBytes(recoveryWrappedVdek),
    FIELD_PASSWORD_WRAP_EPOCH to passwordWrapEpoch,
    FIELD_RECOVERY_WRAP_EPOCH to recoveryWrapEpoch,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt,
    FIELD_META_REVISION to metaRevision
)

private fun DocumentSnapshot.toPrimitiveFields(): Map<String, Any?> =
    data?.mapValues { (_, value) -> if (value is Blob) value.toBytes() else value }
        ?: throw MalformedRemoteDataException()

private fun DocumentSnapshot.toRemoteVaultData(): RemoteVaultData? {
    if (!exists()) return null
    return RemoteDocumentParser.vault(id, toPrimitiveFields())
}

private fun RemoteItemMetadata.toFirestoreMap(ciphertext: Ciphertext): Map<String, Any> = mapOf(
    FIELD_CIPHERTEXT to Blob.fromBytes(ciphertext.bytes),
    FIELD_CRYPTO_VERSION to cryptoVersion,
    FIELD_SCHEMA_VERSION to schemaVersion,
    FIELD_REVISION to revision,
    FIELD_TOMBSTONE to tombstone,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt
)

private fun DocumentSnapshot.toRemoteItem(): Pair<Ciphertext, RemoteItemMetadata>? {
    if (!exists()) return null
    val item = RemoteDocumentParser.item(id, toPrimitiveFields())
    val metadata = RemoteItemMetadata(
        cryptoVersion = item.cryptoVersion,
        schemaVersion = item.schemaVersion,
        revision = item.revision,
        tombstone = item.tombstone,
        createdAt = item.createdAt,
        updatedAt = item.updatedAt
    )
    return Ciphertext.fromPersisted(item.ciphertext) to metadata
}

private fun DocumentSnapshot.toRemoteItemData(): RemoteItemData? {
    if (!exists()) return null
    return RemoteDocumentParser.item(id, toPrimitiveFields())
}

/**
 * Implementación real sobre Firestore. La ruta `users/{uid}/vaults/{vaultId}` ancla la
 * propiedad (ADR-014). Cada operación queda ligada al `expectedUid` capturado por la capa
 * superior y revalida la sesión antes y después de cada espera remota. El uid no entra en
 * la AAD (ADR-009) ni en los documentos.
 */
@Suppress("TooManyFunctions")
class FirestoreVaultSourceImpl(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    // Ver el mismo parámetro en FirebaseAuthSourceImpl.
    emulatorHost: String = EmulatorConfig.DEFAULT_HOST
) : FirestoreVaultSource {

    init {
        EmulatorConfig.configureFirestoreIfDebug(firestore, emulatorHost)
    }

    private fun requireUid(): String =
        auth.currentUser?.uid ?: error("No hay sesión de Firebase Auth activa")

    private fun requireSameAuth(expectedUid: String) {
        check(expectedUid.isNotBlank() && requireUid() == expectedUid) { "auth_session_changed" }
    }

    private fun userDocument(uid: String) = firestore.collection(COLLECTION_USERS).document(uid)

    private fun vaultDocument(expectedUid: String, vaultId: String) = userDocument(expectedUid)
        .collection(COLLECTION_VAULTS).document(vaultId)

    override suspend fun createVaultMeta(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ) {
        requireSameAuth(expectedUid)
        RemoteVaultValidator.requireValid(RemoteVaultData(vaultId, metadata))
        val vault = vaultDocument(expectedUid, vaultId)
        firestore.runTransaction { transaction ->
            requireSameAuth(expectedUid)
            val existing = transaction.get(vault)
            if (existing.exists()) {
                val current = existing.toRemoteVaultData()?.metadata
                    ?: throw MalformedRemoteDataException()
                if (!RemoteVaultValidator.hasSameContent(current, metadata)) {
                    throw MalformedRemoteDataException()
                }
            } else {
                transaction.set(vault, metadata.toFirestoreMap())
            }
            true
        }.awaitResult()
        requireSameAuth(expectedUid)
    }

    override suspend fun updateVaultMeta(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ) {
        requireSameAuth(expectedUid)
        RemoteVaultValidator.requireValid(RemoteVaultData(vaultId, metadata))
        val vault = vaultDocument(expectedUid, vaultId)
        firestore.runTransaction { transaction ->
            requireSameAuth(expectedUid)
            val existing = transaction.get(vault)
            if (!existing.exists()) throw MalformedRemoteDataException()
            val current = existing.toRemoteVaultData()?.metadata
                ?: throw MalformedRemoteDataException()
            if (!RemoteVaultValidator.hasSameContent(current, metadata)) {
                RemoteVaultValidator.requireValidTransition(vaultId, current, metadata)
                transaction.set(vault, metadata.toFirestoreMap())
            }
            true
        }.awaitResult()
        requireSameAuth(expectedUid)
    }

    override suspend fun replaceVaultMetaIfUnchanged(
        expectedUid: String,
        vaultId: String,
        expected: RemoteVaultMetadata,
        replacement: RemoteVaultMetadata,
    ): Boolean {
        requireSameAuth(expectedUid)
        RemoteVaultValidator.requireValid(RemoteVaultData(vaultId, expected))
        RemoteVaultValidator.requireValidTransition(vaultId, expected, replacement)
        val vault = vaultDocument(expectedUid, vaultId)
        val replaced = firestore.runTransaction { transaction ->
            requireSameAuth(expectedUid)
            val current = transaction.get(vault).toRemoteVaultData()?.metadata
                ?: return@runTransaction false
            if (!RemoteVaultValidator.hasSameContent(current, expected)) {
                false
            } else {
                transaction.set(vault, replacement.toFirestoreMap())
                true
            }
        }.awaitResult()
        requireSameAuth(expectedUid)
        return replaced
    }

    override suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata? {
        requireSameAuth(expectedUid)
        val result = vaultDocument(expectedUid, vaultId).get(Source.SERVER).awaitResult()
            .toRemoteVaultData()?.metadata
        requireSameAuth(expectedUid)
        return result
    }

    override suspend fun listVaults(expectedUid: String): List<RemoteVaultData> {
        requireSameAuth(expectedUid)
        val result = userDocument(expectedUid)
            .collection(COLLECTION_VAULTS)
            .get(Source.SERVER).awaitResult()
            .documents
            .map { document -> document.toRemoteVaultData() ?: throw MalformedRemoteDataException() }
        requireSameAuth(expectedUid)
        return result
    }

    override suspend fun uploadItem(
        expectedUid: String,
        vaultId: String,
        itemId: String,
        ciphertext: Ciphertext,
        metadata: RemoteItemMetadata
    ) {
        requireSameAuth(expectedUid)
        // docs/architecture.md §5: el límite de 256 KiB se comprueba en las reglas y
        // también aquí, en el cliente, antes de gastar una escritura de red que el
        // servidor rechazaría igual (ADR-037).
        requireValidItem(
            RemoteItemData(
                id = itemId,
                ciphertext = ciphertext.bytes,
                cryptoVersion = metadata.cryptoVersion,
                schemaVersion = metadata.schemaVersion,
                revision = metadata.revision,
                tombstone = metadata.tombstone,
                createdAt = metadata.createdAt,
                updatedAt = metadata.updatedAt
            )
        )
        vaultDocument(expectedUid, vaultId)
            .collection(COLLECTION_ITEMS).document(itemId)
            .set(metadata.toFirestoreMap(ciphertext))
            .awaitResult()
        requireSameAuth(expectedUid)
    }

    override suspend fun createItemIfAbsentOrIdentical(
        expectedUid: String,
        vaultId: String,
        item: RemoteItemData,
    ): Boolean {
        requireSameAuth(expectedUid)
        requireValidItem(item)
        val document = vaultDocument(expectedUid, vaultId)
            .collection(COLLECTION_ITEMS).document(item.id)
        val accepted = firestore.runTransaction { transaction ->
            requireSameAuth(expectedUid)
            val existing = transaction.get(document)
            if (!existing.exists()) {
                val metadata = RemoteItemMetadata(
                    cryptoVersion = item.cryptoVersion,
                    schemaVersion = item.schemaVersion,
                    revision = item.revision,
                    tombstone = item.tombstone,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                )
                transaction.set(
                    document,
                    metadata.toFirestoreMap(Ciphertext.fromPersisted(item.ciphertext)),
                )
                true
            } else {
                existing.toRemoteItemData() == item
            }
        }.awaitResult()
        requireSameAuth(expectedUid)
        return accepted
    }

    override suspend fun replaceItemIfUnchanged(
        expectedUid: String,
        vaultId: String,
        expected: RemoteItemData?,
        replacement: RemoteItemData,
    ): Boolean {
        requireSameAuth(expectedUid)
        expected?.let(::requireValidItem)
        requireValidItem(replacement)
        require(expected == null || expected.id == replacement.id)
        val document = vaultDocument(expectedUid, vaultId)
            .collection(COLLECTION_ITEMS).document(replacement.id)
        val replaced = firestore.runTransaction { transaction ->
            requireSameAuth(expectedUid)
            val snapshot = transaction.get(document)
            val current = if (snapshot.exists()) snapshot.toRemoteItemData() else null
            if (current != expected) {
                false
            } else {
                val metadata = RemoteItemMetadata(
                    cryptoVersion = replacement.cryptoVersion,
                    schemaVersion = replacement.schemaVersion,
                    revision = replacement.revision,
                    tombstone = replacement.tombstone,
                    createdAt = replacement.createdAt,
                    updatedAt = replacement.updatedAt,
                )
                transaction.set(
                    document,
                    metadata.toFirestoreMap(Ciphertext.fromPersisted(replacement.ciphertext)),
                )
                true
            }
        }.awaitResult()
        requireSameAuth(expectedUid)
        return replaced
    }

    override suspend fun getItem(
        expectedUid: String,
        vaultId: String,
        itemId: String
    ): Pair<Ciphertext, RemoteItemMetadata>? {
        requireSameAuth(expectedUid)
        val result = vaultDocument(expectedUid, vaultId)
            .collection(COLLECTION_ITEMS).document(itemId)
            .get(Source.SERVER).awaitResult()
            .toRemoteItem()
        requireSameAuth(expectedUid)
        return result
    }

    override suspend fun listItems(expectedUid: String, vaultId: String): List<RemoteItemData> {
        requireSameAuth(expectedUid)
        val result = vaultDocument(expectedUid, vaultId)
            .collection(COLLECTION_ITEMS)
            .get(Source.SERVER).awaitResult()
            .documents
            .map { it.toRemoteItemData() ?: throw MalformedRemoteDataException() }
        requireSameAuth(expectedUid)
        return result
    }

    override suspend fun listDeletedVaultIds(expectedUid: String): Set<String> {
        requireSameAuth(expectedUid)
        val snapshots = userDocument(expectedUid)
            .collection(COLLECTION_DELETED_VAULTS)
            .get(Source.SERVER).awaitResult().documents
        val result = snapshots.mapTo(linkedSetOf()) { snapshot ->
            requireValidDeletionMarker(snapshot)
            try {
                UUID.fromString(snapshot.id)
                snapshot.id
            } catch (_: IllegalArgumentException) {
                throw MalformedRemoteDataException()
            }
        }
        requireSameAuth(expectedUid)
        return result
    }

    override suspend fun purgeVault(expectedUid: String, vaultId: String, deletedAt: Long) {
        require(deletedAt > 0L)
        requireSameAuth(expectedUid)
        val userDocument = userDocument(expectedUid)
        val vault = userDocument.collection(COLLECTION_VAULTS).document(vaultId)
        val marker = userDocument.collection(COLLECTION_DELETED_VAULTS).document(vaultId)
        firestore.runTransaction { transaction ->
            requireSameAuth(expectedUid)
            val existing = transaction.get(marker)
            if (existing.exists()) {
                requireValidDeletionMarker(existing)
            } else {
                transaction.set(
                    marker,
                    mapOf(FIELD_SCHEMA_VERSION to 1, FIELD_DELETED_AT to deletedAt)
                )
            }
            true
        }.awaitResult()

        requireSameAuth(expectedUid)
        val items = vault.collection(COLLECTION_ITEMS)
        var pageWasEmpty: Boolean
        do {
            requireSameAuth(expectedUid)
            val page = items.limit(PURGE_PAGE_SIZE).get(Source.SERVER).awaitResult()
            pageWasEmpty = page.isEmpty
            page.documents.forEach { document ->
                document.reference.delete().awaitResult()
                requireSameAuth(expectedUid)
            }
        } while (!pageWasEmpty)
        if (!items.limit(1).get(Source.SERVER).awaitResult().isEmpty) {
            throw MalformedRemoteDataException()
        }
        requireSameAuth(expectedUid)
        vault.delete().awaitResult()
        requireSameAuth(expectedUid)
    }

    private fun requireValidDeletionMarker(snapshot: DocumentSnapshot) {
        val valid = snapshot.data?.let { fields ->
            fields.keys == setOf(FIELD_SCHEMA_VERSION, FIELD_DELETED_AT) &&
                fields[FIELD_SCHEMA_VERSION] == 1L &&
                ((fields[FIELD_DELETED_AT] as? Long)?.let { it > 0L } == true)
        } == true
        if (!valid) throw MalformedRemoteDataException()
    }

    private companion object {
        const val PURGE_PAGE_SIZE = 100L
    }
}
