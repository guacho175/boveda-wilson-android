package cl.bovedawilson.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int,

    @ColumnInfo(name = "lastPullAt")
    val lastPullAt: Long,

    @ColumnInfo(name = "lastPushAt")
    val lastPushAt: Long,

    @ColumnInfo(name = "lastError")
    val lastError: String?
)
