package cl.bovedawilson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "biometric_unlock")
data class BiometricUnlockEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "keyAlias")
    val keyAlias: String,

    @ColumnInfo(name = "wrappedBiometricKek")
    val wrappedBiometricKek: ByteArray,

    @ColumnInfo(name = "biometricWrappedVdek")
    val biometricWrappedVdek: ByteArray,

    @ColumnInfo(name = "biometricWrapEpoch")
    val biometricWrapEpoch: Int,

    @ColumnInfo(name = "iv")
    val iv: ByteArray,

    @ColumnInfo(name = "strongBoxBacked")
    val strongBoxBacked: Boolean,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BiometricUnlockEntity

        if (id != other.id) return false
        if (keyAlias != other.keyAlias) return false
        if (!wrappedBiometricKek.contentEquals(other.wrappedBiometricKek)) return false
        if (!biometricWrappedVdek.contentEquals(other.biometricWrappedVdek)) return false
        if (biometricWrapEpoch != other.biometricWrapEpoch) return false
        if (!iv.contentEquals(other.iv)) return false
        if (strongBoxBacked != other.strongBoxBacked) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + keyAlias.hashCode()
        result = 31 * result + wrappedBiometricKek.contentHashCode()
        result = 31 * result + biometricWrappedVdek.contentHashCode()
        result = 31 * result + biometricWrapEpoch
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + strongBoxBacked.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
