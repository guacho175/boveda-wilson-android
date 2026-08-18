package cl.bovedawilson.data.sync.session

import cl.bovedawilson.core.crypto.session.UnlockedVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SessionState {
    data object Locked : SessionState()
    data class Unlocked(val vaultId: String, val openedAt: Long) : SessionState()
}

class UnlockLease internal constructor(internal val generation: Long)

class VaultSession {
    private val _state = MutableStateFlow<SessionState>(SessionState.Locked)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var unlockedVault: UnlockedVault? = null
    private var unlockGeneration = 0L
    private var foreground = true

    @Synchronized
    fun beginUnlock(): UnlockLease? = if (foreground) UnlockLease(unlockGeneration) else null

    @Synchronized
    fun isUnlockLeaseValid(lease: UnlockLease): Boolean =
        foreground && lease.generation == unlockGeneration && _state.value is SessionState.Locked

    /**
     * @param vault La bóveda desbloqueada proporcionada por :core:crypto
     * @param vaultId Identificador de la bóveda/propietario
     * @param currentTimeMs Tiempo de apertura
     */
    @Synchronized
    fun tryUnlock(
        lease: UnlockLease,
        vault: UnlockedVault,
        vaultId: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isUnlockLeaseValid(lease)) return false
        unlockedVault = vault
        _state.value = SessionState.Unlocked(vaultId, currentTimeMs)
        return true
    }

    /**
     * Bloquea la bóveda y limpia el estado de la sesión.
     */
    @Synchronized
    fun lock() {
        unlockGeneration++
        unlockedVault = null
        _state.value = SessionState.Locked
    }

    @Synchronized
    fun onAppBackgrounded() {
        foreground = false
        unlockGeneration++
    }

    @Synchronized
    fun onAppForegrounded() {
        foreground = true
    }

    /**
     * @return null si la sesión está bloqueada.
     */
    @Synchronized
    fun getVault(): UnlockedVault? = unlockedVault

    /** Marca monotónica solo en memoria; cambia cada vez que una sesión queda invalidada. */
    @Synchronized
    internal fun securityGeneration(): Long = unlockGeneration
}
