package cl.bovedawilson.core.model

class VaultItemField(
    val k: String,
    val v: String,
    val secret: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItemField) return false
        return k == other.k && v == other.v && secret == other.secret
    }

    override fun hashCode(): Int {
        var result = k.hashCode()
        result = 31 * result + v.hashCode()
        result = 31 * result + secret.hashCode()
        return result
    }

    override fun toString(): String = "VaultItemField(k=${k.length}c, v=${v.length}c, secret=$secret)"
}

@Suppress("LongParameterList")
data class VaultItem(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val fields: List<VaultItemField>,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun toString(): String =
        "VaultItem(id=$id, title=${title.length}c, body=${body.length}c, tags=${tags.size}, " +
            "fields=${fields.size}, redacted)"
}
