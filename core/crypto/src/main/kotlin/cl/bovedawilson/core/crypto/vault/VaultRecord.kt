package cl.bovedawilson.core.crypto.vault

import cl.bovedawilson.core.crypto.kdf.KdfParameters
import cl.bovedawilson.core.crypto.version.CryptoVersion
import cl.bovedawilson.core.crypto.version.SchemaVersion
import cl.bovedawilson.core.crypto.wrap.WrappedVdek

/**
 * Envoltorio de la VDEK por el camino de la contraseña maestra: parámetros de KDF, el
 * keyset cifrado de Tink y su epoch monótono (`docs/architecture.md` §4, ADR-030).
 *
 * No es `data class`: [parameters] contiene un `ByteArray` (el salt) cuya igualdad
 * estructural por defecto compararía referencias, no contenido.
 */
class PasswordWrap(
    val parameters: KdfParameters,
    val wrappedVdek: WrappedVdek,
    val epoch: Int,
)

/**
 * Envoltorio de la VDEK por el camino de la frase de recuperación: el salt, el keyset
 * cifrado de Tink y su epoch monótono (`docs/architecture.md` §4, ADR-030).
 *
 * No es `data class`: [salt] es un `ByteArray` (misma razón que [PasswordWrap]).
 */
class RecoveryWrap(
    val salt: ByteArray,
    val wrappedVdek: WrappedVdek,
    val epoch: Int,
)

/**
 * Envoltorio criptográfico completo de una bóveda: los campos no biométricos de `vault_meta`
 * (`docs/architecture.md` §4), sin `ownerUid` ni metadatos de sincronización — eso es
 * responsabilidad de `:data:local`/`:data:remote`. Todos los campos son públicos y no
 * sensibles: identificadores, versiones, parámetros de KDF, salts y keysets ya envueltos.
 */
class VaultRecord(
    val vaultId: String,
    val cryptoVersion: CryptoVersion,
    val schemaVersion: SchemaVersion,
    val password: PasswordWrap,
    val recovery: RecoveryWrap,
) {
    override fun toString(): String = "VaultRecord(vaultId=$vaultId, cryptoVersion=${cryptoVersion.value})"
}
