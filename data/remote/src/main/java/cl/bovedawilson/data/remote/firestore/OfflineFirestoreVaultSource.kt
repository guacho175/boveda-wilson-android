package cl.bovedawilson.data.remote.firestore

import cl.bovedawilson.core.crypto.ciphertext.Ciphertext

/**
 * Implementación usada cuando **no hay un proyecto Firebase configurado** en la
 * instalación: sin `google-services.json` no existe `FirebaseApp` por defecto y cualquier
 * llamada a `FirebaseFirestore.getInstance()` lanzaría al construir el grafo de
 * dependencias, tumbando la aplicación en el arranque (bloqueo externo B-01 de
 * `PROJECT_STATE.md`).
 *
 * La bóveda es local-first: crear, desbloquear, leer y escribir notas no necesitan red.
 * Lo único que queda inhabilitado es la sincronización, y se declara como tal en vez de
 * fingir un resultado vacío que el motor de sincronización interpretaría como «el
 * servidor no tiene nada», borrando el estado remoto real de un usuario que sí lo tenga.
 */
class OfflineFirestoreVaultSource : FirestoreVaultSource {

    override suspend fun createVaultMeta(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ): Unit = unavailable()

    override suspend fun updateVaultMeta(
        expectedUid: String,
        vaultId: String,
        metadata: RemoteVaultMetadata
    ): Unit =
        unavailable()

    override suspend fun getVaultMeta(expectedUid: String, vaultId: String): RemoteVaultMetadata? = unavailable()

    override suspend fun listVaults(expectedUid: String): List<RemoteVaultData> = unavailable()

    override suspend fun uploadItem(
        expectedUid: String,
        vaultId: String,
        itemId: String,
        ciphertext: Ciphertext,
        metadata: RemoteItemMetadata
    ): Unit = unavailable()

    override suspend fun getItem(
        expectedUid: String,
        vaultId: String,
        itemId: String
    ): Pair<Ciphertext, RemoteItemMetadata>? = unavailable()

    override suspend fun listItems(expectedUid: String, vaultId: String): List<RemoteItemData> = unavailable()

    override suspend fun listDeletedVaultIds(expectedUid: String): Set<String> = unavailable()

    override suspend fun purgeVault(expectedUid: String, vaultId: String, deletedAt: Long): Unit = unavailable()

    private fun unavailable(): Nothing =
        throw RemoteUnavailableException("remote_not_configured")
}

/**
 * Señala que la capa remota no está configurada. El mensaje es una categoría fija, sin
 * identificadores ni material (`SECURITY.md` §4).
 */
class RemoteUnavailableException(message: String) : IllegalStateException(message)
