package cl.bovedawilson.data.sync.dto

/**
 * DTO para representar un ítem remoto durante sincronización.
 *
 * Contiene solo ciphertext opaco y metadatos mínimos (revisión, estado de borrado,
 * marcas de tiempo). No descifera contenido, que es responsabilidad de capas superiores.
 */
data class RemoteItemDto(
    val id: String,
    val ciphertext: ByteArray,
    val cryptoVersion: Int,
    val schemaVersion: Int,
    val revision: Int,
    val tombstone: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RemoteItemDto
        return id == other.id &&
            ciphertext.contentEquals(other.ciphertext) &&
            cryptoVersion == other.cryptoVersion &&
            schemaVersion == other.schemaVersion &&
            revision == other.revision &&
            tombstone == other.tombstone &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + cryptoVersion
        result = 31 * result + schemaVersion
        result = 31 * result + revision
        result = 31 * result + tombstone.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
