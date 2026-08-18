package cl.bovedawilson.core.crypto.session

import cl.bovedawilson.core.crypto.aead.Aad
import cl.bovedawilson.core.crypto.ciphertext.Ciphertext
import cl.bovedawilson.core.crypto.item.ItemCryptor
import cl.bovedawilson.core.crypto.item.ItemPayload
import cl.bovedawilson.core.crypto.keys.VdekFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import java.security.GeneralSecurityException

/**
 * Capacidad opaca de una bóveda desbloqueada. El `Aead` de la VDEK es privado: ninguna API
 * pública de este tipo lo expone (`docs/architecture.md` §2, §3, ADR-033). El constructor
 * es interno: fuera de `:core:crypto` solo se puede **recibir** una instancia ya creada,
 * nunca fabricar una.
 */
class UnlockedVault internal constructor(private val vdekHandle: KeysetHandle) {

    private val aead: Aead by lazy(LazyThreadSafetyMode.NONE) { vdekHandle.getPrimitive(Aead::class.java) }

    fun encrypt(payload: ItemPayload, aad: Aad): Ciphertext = ItemCryptor.encrypt(aead, payload, aad)

    fun decrypt(ciphertext: Ciphertext, aad: Aad): ItemPayload = ItemCryptor.decrypt(aead, ciphertext, aad)

    /**
     * Autentica un manifiesto de respaldo canónico sin exponer la VDEK ni inventar una MAC.
     * Tink cifra un plaintext vacío y liga el resultado al manifiesto completo como AAD.
     */
    fun authenticateBackupManifest(canonicalManifest: ByteArray): ByteArray =
        aead.encrypt(EMPTY_PLAINTEXT, backupManifestAad(canonicalManifest))

    /** Verifica el autenticador Tink del manifiesto; cualquier alteración falla cerrada. */
    fun verifiesBackupManifest(canonicalManifest: ByteArray, authenticator: ByteArray): Boolean = try {
        aead.decrypt(authenticator, backupManifestAad(canonicalManifest)).isEmpty()
    } catch (_: GeneralSecurityException) {
        false
    }

    /** Solo para operaciones de envoltorio dentro de `:core:crypto` (cambio de contraseña,
     * regeneración de la frase). No se expone fuera del módulo. */
    internal fun handleForWrapping(): KeysetHandle = vdekHandle

    companion object {
        private val BACKUP_MANIFEST_PREFIX = "bw2|backup-manifest|".toByteArray(Charsets.UTF_8)
        private val EMPTY_PLAINTEXT = ByteArray(0)

        private fun backupManifestAad(canonicalManifest: ByteArray): ByteArray =
            BACKUP_MANIFEST_PREFIX + canonicalManifest

        internal fun withNewVdek(): UnlockedVault = UnlockedVault(VdekFactory.generate())
        internal fun fromHandle(handle: KeysetHandle): UnlockedVault = UnlockedVault(handle)
    }
}
