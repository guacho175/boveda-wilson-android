package cl.bovedawilson.data.sync.repo

import cl.bovedawilson.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.flow.Flow

/**
 * Fachada de las preferencias de seguridad para `:app` (ADR-019 punto 6): `:app` no
 * puede depender de `:data:local` directamente (`docs/architecture.md` §3), así que
 * las pantallas de seguridad pasan por aquí en vez de por [SettingsDataStore]. Ninguna
 * preferencia expuesta contiene secretos: solo un minutaje y banderas.
 */
class SettingsRepository(
    private val settings: SettingsDataStore
) {
    val lockTimeoutMinutes: Flow<Int> = settings.lockTimeoutMinutes
    val lockOnBackground: Flow<Boolean> = settings.lockOnBackground
    val biometricEnabled: Flow<Boolean> = settings.biometricEnabled

    suspend fun setLockTimeoutMinutes(minutes: Int) = settings.setLockTimeoutMinutes(minutes)

    suspend fun setLockOnBackground(enabled: Boolean) = settings.setLockOnBackground(enabled)

    suspend fun setBiometricEnabled(enabled: Boolean) = settings.setBiometricEnabled(enabled)
}
