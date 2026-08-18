package cl.bovedawilson.core.crypto.version

/** Versión del esquema del contenido del ítem (`CRYPTOGRAPHY.md` §12). */
@JvmInline
value class SchemaVersion(val value: Int) {
    companion object {
        val V1 = SchemaVersion(1)
    }
}
