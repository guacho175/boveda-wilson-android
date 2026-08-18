package cl.bovedawilson.data.local.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PendingVaultDeletion(
    val vaultId: String,
    val requiresRemotePurge: Boolean,
    val signOutAfterDeletion: Boolean
)

class SettingsDataStore(
    private val dataStore: DataStore<Preferences>
) {
    val lockTimeoutMinutes: Flow<Int> = dataStore.data.map { it[LOCK_TIMEOUT] ?: DEFAULT_LOCK_TIMEOUT_MINUTES }
    val lockOnBackground: Flow<Boolean> = dataStore.data.map { it[LOCK_ON_BACKGROUND] ?: true }
    val biometricEnabled: Flow<Boolean> = dataStore.data.map { it[BIOMETRIC_ENABLED] ?: false }
    val pendingVaultDeletion: Flow<PendingVaultDeletion?> = dataStore.data.map { preferences ->
        val vaultId = preferences[PENDING_DELETION_VAULT_ID]
        if (vaultId.isNullOrBlank()) {
            null
        } else {
            PendingVaultDeletion(
                vaultId = vaultId,
                requiresRemotePurge = preferences[PENDING_DELETION_REQUIRES_REMOTE] ?: false,
                signOutAfterDeletion = preferences[PENDING_DELETION_SIGN_OUT] ?: false
            )
        }
    }

    suspend fun setLockTimeoutMinutes(minutes: Int) {
        dataStore.edit { it[LOCK_TIMEOUT] = minutes }
    }

    suspend fun setLockOnBackground(enabled: Boolean) {
        dataStore.edit { it[LOCK_ON_BACKGROUND] = enabled }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun markVaultDeletionPending(
        vaultId: String,
        requiresRemotePurge: Boolean,
        signOutAfterDeletion: Boolean
    ) {
        require(vaultId.isNotBlank())
        dataStore.edit { preferences ->
            preferences[PENDING_DELETION_VAULT_ID] = vaultId
            preferences[PENDING_DELETION_REQUIRES_REMOTE] = requiresRemotePurge
            preferences[PENDING_DELETION_SIGN_OUT] = signOutAfterDeletion
        }
    }

    suspend fun markRemotePurgeComplete() {
        dataStore.edit { preferences -> preferences[PENDING_DELETION_REQUIRES_REMOTE] = false }
    }

    suspend fun clearVaultDeletionPending() {
        dataStore.edit { preferences ->
            preferences.remove(PENDING_DELETION_VAULT_ID)
            preferences.remove(PENDING_DELETION_REQUIRES_REMOTE)
            preferences.remove(PENDING_DELETION_SIGN_OUT)
        }
    }

    companion object {
        private const val DEFAULT_LOCK_TIMEOUT_MINUTES = 5
        private val LOCK_TIMEOUT = intPreferencesKey("lock_timeout_minutes")
        private val LOCK_ON_BACKGROUND = booleanPreferencesKey("lock_on_background")
        private val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        private val PENDING_DELETION_VAULT_ID = stringPreferencesKey("pending_deletion_vault_id")
        private val PENDING_DELETION_REQUIRES_REMOTE =
            booleanPreferencesKey("pending_deletion_requires_remote")
        private val PENDING_DELETION_SIGN_OUT = booleanPreferencesKey("pending_deletion_sign_out")
    }
}
