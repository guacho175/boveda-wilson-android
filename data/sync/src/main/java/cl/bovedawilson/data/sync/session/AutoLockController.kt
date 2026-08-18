package cl.bovedawilson.data.sync.session

import android.os.SystemClock
import cl.bovedawilson.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutoLockController(
    private val session: VaultSession,
    private val settings: SettingsDataStore,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private var lockJob: Job? = null

    @Volatile
    private var isAppInBackground = false

    @Volatile
    private var lastInteractionTime = nowMillis()
    private var sessionWasUnlocked = false

    init {
        scope.launch {
            combine(
                session.state,
                settings.lockTimeoutMinutes,
                settings.lockOnBackground
            ) { state, timeoutMinutes, lockOnBackground ->
                Triple(state, timeoutMinutes, lockOnBackground)
            }.collect { (state, timeoutMinutes, lockOnBackground) ->
                if (state is SessionState.Unlocked) {
                    if (!sessionWasUnlocked) {
                        // Una sesión recién abierta recibe su propio plazo completo. No se
                        // reutiliza el instante de interacción de la sesión anterior.
                        lastInteractionTime = nowMillis()
                    }
                    sessionWasUnlocked = true
                    scheduleLock(timeoutMinutes, lockOnBackground)
                } else {
                    sessionWasUnlocked = false
                    cancelLock()
                }
            }
        }
    }

    fun onAppBackgrounded() {
        isAppInBackground = true
        // Invalida de forma síncrona cualquier KDF/prompt iniciado en foreground.
        session.onAppBackgrounded()
        checkImmediateLock()
    }

    fun onAppForegrounded() {
        isAppInBackground = false
        session.onAppForegrounded()
        // Volver al primer plano no es interacción. Si la app pasó más tiempo fuera que
        // el plazo configurado (y el proceso estuvo suspendido), el bucle debe bloquearla
        // inmediatamente en vez de regalar un plazo nuevo al abrirla.
    }

    fun onUserInteraction() {
        lastInteractionTime = nowMillis()
    }

    private fun checkImmediateLock() {
        scope.launch {
            val state = session.state.value
            if (state is SessionState.Unlocked && settings.lockOnBackground.first()) {
                session.lock()
            }
        }
    }

    private fun cancelLock() {
        lockJob?.cancel()
        lockJob = null
    }

    private fun scheduleLock(timeoutMinutes: Int, lockOnBackground: Boolean) {
        cancelLock()
        lockJob = scope.launch {
            while (true) {
                delay(LOCK_CHECK_INTERVAL_MS)
                val elapsed = nowMillis() - lastInteractionTime
                val timeoutMs = timeoutMinutes * MILLIS_PER_MINUTE
                val timedOut = elapsed >= timeoutMs
                val backgroundLock = isAppInBackground && lockOnBackground
                if (timedOut || backgroundLock) {
                    session.lock()
                    break
                }
            }
        }
    }

    private companion object {
        const val LOCK_CHECK_INTERVAL_MS = 1000L
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
