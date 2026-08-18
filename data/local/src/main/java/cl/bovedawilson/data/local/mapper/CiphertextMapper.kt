package cl.bovedawilson.data.local.mapper

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext

/**
 * Mapper para unwrapping de Ciphertext desde bytes persistidos.
 * Este archivo es permitido usar `fromPersisted()` según G-74.
 * Acceso desde `:data:sync` para resolver conflictos de sincronización.
 */
object CiphertextMapper {
    fun fromPersistedBytes(bytes: ByteArray): Ciphertext {
        return Ciphertext.fromPersisted(bytes)
    }
}
