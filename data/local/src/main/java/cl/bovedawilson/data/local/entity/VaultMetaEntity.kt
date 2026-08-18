package cl.bovedawilson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_meta")
data class VaultMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "vaultId")
    val vaultId: String,
    @ColumnInfo(name = "ownerUid")
    val ownerUid: String,
    @ColumnInfo(name = "schemaVersion")
    val schemaVersion: Int,
    @ColumnInfo(name = "cryptoVersion")
    val cryptoVersion: Int,
    @ColumnInfo(name = "kdfName")
    val kdfName: String,
    @ColumnInfo(name = "kdfMemoryKib")
    val kdfMemoryKib: Int,
    @ColumnInfo(name = "kdfIterations")
    val kdfIterations: Int,
    @ColumnInfo(name = "kdfParallelism")
    val kdfParallelism: Int,
    @ColumnInfo(name = "kdfOutputLen")
    val kdfOutputLen: Int,
    @ColumnInfo(name = "passwordSalt")
    val passwordSalt: ByteArray,
    @ColumnInfo(name = "passwordWrappedVdek")
    val passwordWrappedVdek: ByteArray,
    @ColumnInfo(name = "recoverySalt")
    val recoverySalt: ByteArray,
    @ColumnInfo(name = "recoveryWrappedVdek")
    val recoveryWrappedVdek: ByteArray,
    @ColumnInfo(name = "passwordWrapEpoch")
    val passwordWrapEpoch: Int,
    @ColumnInfo(name = "recoveryWrapEpoch")
    val recoveryWrapEpoch: Int,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
    @ColumnInfo(name = "metaRevision")
    val metaRevision: Int
) {
    // Ver el mismo patrón (escalares vs. arrays) en EncryptedItemEntity.equals().
    private fun scalarFields(): List<Any?> = listOf(
        vaultId, ownerUid, schemaVersion, cryptoVersion, kdfName, kdfMemoryKib, kdfIterations,
        kdfParallelism, kdfOutputLen, passwordWrapEpoch, recoveryWrapEpoch, createdAt, updatedAt,
        metaRevision
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VaultMetaEntity

        return scalarFields() == other.scalarFields() &&
            passwordSalt.contentEquals(other.passwordSalt) &&
            passwordWrappedVdek.contentEquals(other.passwordWrappedVdek) &&
            recoverySalt.contentEquals(other.recoverySalt) &&
            recoveryWrappedVdek.contentEquals(other.recoveryWrappedVdek)
    }

    override fun hashCode(): Int {
        var result = vaultId.hashCode()
        result = 31 * result + ownerUid.hashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + cryptoVersion
        result = 31 * result + kdfName.hashCode()
        result = 31 * result + kdfMemoryKib
        result = 31 * result + kdfIterations
        result = 31 * result + kdfParallelism
        result = 31 * result + kdfOutputLen
        result = 31 * result + passwordSalt.contentHashCode()
        result = 31 * result + passwordWrappedVdek.contentHashCode()
        result = 31 * result + recoverySalt.contentHashCode()
        result = 31 * result + recoveryWrappedVdek.contentHashCode()
        result = 31 * result + passwordWrapEpoch
        result = 31 * result + recoveryWrapEpoch
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + metaRevision
        return result
    }
}
