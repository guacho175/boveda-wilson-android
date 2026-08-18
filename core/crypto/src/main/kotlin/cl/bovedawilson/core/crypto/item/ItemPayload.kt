package cl.bovedawilson.core.crypto.item

import kotlinx.serialization.Serializable

/**
 * Campo personalizado de un ítem (`CRYPTOGRAPHY.md` §9). No es `data class`: tanto la clave
 * como el valor son contenido de la nota, y `SECURITY.md` §1 prohíbe `toString()`
 * generado y `data class` para tipos con campos de secreto. [equals]/[hashCode] se implementan
 * a mano para conservar la igualdad estructural que las pruebas necesitan.
 */
@Serializable
class ItemField(
    val k: String,
    val v: String,
    val secret: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemField) return false
        return k == other.k && v == other.v && secret == other.secret
    }

    override fun hashCode(): Int {
        var result = k.hashCode()
        result = 31 * result + v.hashCode()
        result = 31 * result + secret.hashCode()
        return result
    }

    override fun toString(): String = "ItemField(k=${k.length}c, v=${v.length}c, secret=$secret)"
}

/**
 * Esquema JSON estricto del contenido descifrado de un ítem. Todo lo sensible vive
 * **dentro** del ciphertext; fuera solo quedan identificadores y metadatos no sensibles
 * (`CRYPTOGRAPHY.md` §9). Esta clase nunca sale de `:core:crypto`: `:data:sync` mapea
 * `VaultItem` (`:core:model`) hacia y desde ella.
 *
 * No es `data class` por la misma razón que [ItemField]: `title`, `body`, `tags` y `fields`
 * son el contenido en claro de la nota, y su `toString()`/`equals()` por defecto expondrían
 * ese contenido en cualquier registro, aserción fallida o mensaje de excepción accidental.
 *
 * Los siete parámetros son exactamente el esquema JSON congelado de `CRYPTOGRAPHY.md` §9;
 * agruparlos para bajar de siete cambiaría el formato persistido sin necesidad real.
 */
@Suppress("LongParameterList")
@Serializable
class ItemPayload(
    val v: Int,
    val title: String,
    val body: String,
    val tags: List<String>,
    val fields: List<ItemField>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ItemPayload) return false
        return v == other.v &&
            title == other.title &&
            body == other.body &&
            tags == other.tags &&
            fields == other.fields &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = v
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + fields.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "ItemPayload(v=$v, title=${title.length}c, body=${body.length}c, tags=${tags.size}, " +
            "fields=${fields.size}, redacted)"
}
