package cl.bovedawilson.core.crypto.keys

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/**
 * Genera la VDEK: un keyset de Tink con una única clave primaria AES256-GCM, variante con
 * prefijo (la plantilla estándar recomendada por Tink para datos generales,
 * `CRYPTOGRAPHY.md` §4). Interno: la VDEK nunca sale de `:core:crypto` sin envolver.
 */
internal object VdekFactory {
    init {
        AeadConfig.register()
    }

    fun generate(): KeysetHandle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
}
