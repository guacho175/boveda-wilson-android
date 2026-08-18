package cl.bovedawilson.data.local.store

import cl.bovedawilson.data.local.dao.VaultMetaDao
import cl.bovedawilson.data.local.entity.VaultMetaEntity
import kotlinx.coroutines.flow.Flow

interface VaultMetaStore {
    suspend fun getMeta(): VaultMetaEntity?
    fun observeMeta(): Flow<VaultMetaEntity?>
    suspend fun saveMeta(meta: VaultMetaEntity)
    suspend fun replaceIfUnchanged(expected: VaultMetaEntity, replacement: VaultMetaEntity): Boolean = false
    suspend fun deleteAll()
}

class RoomVaultMetaStore(private val dao: VaultMetaDao) : VaultMetaStore {
    override suspend fun getMeta(): VaultMetaEntity? = dao.getMeta()
    override fun observeMeta(): Flow<VaultMetaEntity?> = dao.observeMeta()
    override suspend fun saveMeta(meta: VaultMetaEntity) = dao.insertOrUpdate(meta)
    override suspend fun replaceIfUnchanged(expected: VaultMetaEntity, replacement: VaultMetaEntity): Boolean =
        dao.replaceIfUnchanged(expected, replacement)
    override suspend fun deleteAll() = dao.deleteAll()
}
