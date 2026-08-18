package cl.bovedawilson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "encrypted_items",
    indices = [
        Index(value = ["dirty"]),
        Index(value = ["tombstone"]),
        Index(value = ["updatedAt"])
    ]
)
data class EncryptedItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "itemId")
    val itemId: String,

    @ColumnInfo(name = "ciphertext")
    val ciphertext: ByteArray,
    @ColumnInfo(name = "cryptoVersion")
    val cryptoVersion: Int,
    @ColumnInfo(name = "schemaVersion")
    val schemaVersion: Int,
    @ColumnInfo(name = "revision")
    val revision: Int,
    @ColumnInfo(name = "tombstone")
    val tombstone: Boolean,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "dirty")
    val dirty: Boolean,
    @ColumnInfo(name = "lastSyncedRevision")
    val lastSyncedRevision: Int,
    @ColumnInfo(name = "conflictOf")
    val conflictOf: String?,

    @ColumnInfo(name = "pendingRemoteCiphertext")
    val pendingRemoteCiphertext: ByteArray?,
    @ColumnInfo(name = "pendingRemoteRevision")
    val pendingRemoteRevision: Int?,
    @ColumnInfo(name = "pendingRemoteCryptoVersion")
    val pendingRemoteCryptoVersion: Int?,
    @ColumnInfo(name = "pendingRemoteSchemaVersion")
    val pendingRemoteSchemaVersion: Int?,
    @ColumnInfo(name = "pendingRemoteTombstone")
    val pendingRemoteTombstone: Boolean?,
    @ColumnInfo(name = "pendingRemoteCreatedAt")
    val pendingRemoteCreatedAt: Long?,
    @ColumnInfo(name = "pendingRemoteUpdatedAt")
    val pendingRemoteUpdatedAt: Long?
) {
    // Comparación en dos grupos (escalares vía List.equals(), arrays vía contentEquals)
    // para que la complejidad ciclomática se quede dentro del umbral de Detekt sin
    // perder la comparación por contenido de los campos ByteArray (G-... higiene de
    // arrays; un equals generado por Room compararía por referencia).
    private fun scalarFields(): List<Any?> = listOf(
        itemId, cryptoVersion, schemaVersion, revision, tombstone, createdAt, updatedAt,
        dirty, lastSyncedRevision, conflictOf, pendingRemoteRevision, pendingRemoteCryptoVersion,
        pendingRemoteSchemaVersion, pendingRemoteTombstone, pendingRemoteCreatedAt, pendingRemoteUpdatedAt
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedItemEntity

        return scalarFields() == other.scalarFields() &&
            ciphertext.contentEquals(other.ciphertext) &&
            pendingRemoteCiphertext.contentEquals(other.pendingRemoteCiphertext)
    }

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + cryptoVersion
        result = 31 * result + schemaVersion
        result = 31 * result + revision
        result = 31 * result + tombstone.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + dirty.hashCode()
        result = 31 * result + lastSyncedRevision
        result = 31 * result + (conflictOf?.hashCode() ?: 0)
        result = 31 * result + (pendingRemoteCiphertext?.contentHashCode() ?: 0)
        result = 31 * result + (pendingRemoteRevision ?: 0)
        result = 31 * result + (pendingRemoteCryptoVersion ?: 0)
        result = 31 * result + (pendingRemoteSchemaVersion ?: 0)
        result = 31 * result + (pendingRemoteTombstone?.hashCode() ?: 0)
        result = 31 * result + (pendingRemoteCreatedAt?.hashCode() ?: 0)
        result = 31 * result + (pendingRemoteUpdatedAt?.hashCode() ?: 0)
        return result
    }
}
