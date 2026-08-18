package cl.bovedawilson.data.sync.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Barrera de proceso compartida por sincronización, restauración, escrituras locales,
 * cambios de identidad y borrado. Ninguna de esas mutaciones puede intercalarse con otra.
 */
class SyncCoordinator {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
