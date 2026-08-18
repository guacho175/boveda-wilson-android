package cl.bovedawilson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_conflicts")
data class PendingConflictEntity(
    @PrimaryKey
    @ColumnInfo(name = "itemId")
    val itemId: String,

    @ColumnInfo(name = "detectedAt")
    val detectedAt: Long,

    @ColumnInfo(name = "remoteRevision")
    val remoteRevision: Int
)
