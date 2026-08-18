package cl.bovedawilson.data.sync.mapper

import cl.bovedawilson.core.crypto.item.ItemField
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.model.VaultItem
import cl.bovedawilson.core.model.VaultItemField

object VaultItemMapper {
    fun toPayload(item: VaultItem, version: Int = 1): ItemPayload {
        return ItemPayload(
            v = version,
            title = item.title,
            body = item.body,
            tags = item.tags,
            fields = item.fields.map { ItemField(it.k, it.v, it.secret) },
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }

    fun toDomain(id: String, payload: ItemPayload): VaultItem {
        return VaultItem(
            id = id,
            title = payload.title,
            body = payload.body,
            tags = payload.tags,
            fields = payload.fields.map { VaultItemField(it.k, it.v, it.secret) },
            createdAt = payload.createdAt,
            updatedAt = payload.updatedAt
        )
    }
}
