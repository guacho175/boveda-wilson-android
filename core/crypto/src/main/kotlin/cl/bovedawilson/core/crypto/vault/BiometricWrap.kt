package cl.bovedawilson.core.crypto.vault

import cl.bovedawilson.core.crypto.wrap.WrappedVdek

/**
 * Envoltorio de la VDEK por el camino biométrico: el alias de la clave del Keystore que
 * protege la `BiometricKEK`, el keyset cifrado de Tink y su epoch monótono
 * (`docs/key-lifecycle.md` §9, ADR-028). Nunca se sincroniza ni entra en el respaldo: solo
 * vive en `BiometricUnlockEntity`, local al dispositivo.
 *
 * No es `data class`: [wrappedVdek] envuelve un `ByteArray` (misma razón que
 * [PasswordWrap]/[RecoveryWrap]).
 */
class BiometricWrap(
    val alias: String,
    val wrappedVdek: WrappedVdek,
    val epoch: Int,
)
