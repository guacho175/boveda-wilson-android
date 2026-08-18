package cl.bovedawilson.app.ui.backup

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupSafAction { Export, Restore, Publish }

class PendingBackupSaf(val action: BackupSafAction, val uri: Uri) {
    override fun toString(): String = "PendingBackupSaf(action=$action, uri=[REDACTED])"
}

/** URI público de SAF, solo en memoria; permite reanudar después del bloqueo al salir al picker. */
@Singleton
class PendingBackupSafStore @Inject constructor() {
    private val _pending = MutableStateFlow<PendingBackupSaf?>(null)
    val pending: StateFlow<PendingBackupSaf?> = _pending.asStateFlow()

    fun set(action: BackupSafAction, uri: Uri) {
        _pending.value = PendingBackupSaf(action, uri)
    }

    fun clear(action: BackupSafAction) {
        if (_pending.value?.action == action) _pending.value = null
    }
}
