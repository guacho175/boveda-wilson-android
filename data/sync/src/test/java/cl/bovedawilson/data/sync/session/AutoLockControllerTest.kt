package cl.bovedawilson.data.sync.session

import cl.bovedawilson.data.local.prefs.SettingsDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockControllerTest {

    @Test
    fun `bloquea inmediatamente al ir al fondo cuando la preferencia esta activa`() = runTest {
        val session = mockSession(MutableStateFlow(unlockedState()))
        val settings = mockSettings(timeoutMinutes = 5, lockOnBackground = true)
        val controller = AutoLockController(session, settings, backgroundScope) { 0L }
        runCurrent()

        controller.onAppBackgrounded()
        runCurrent()

        verify(session).lock()
    }

    @Test
    fun `el tiempo en segundo plano cuenta como inactividad aunque no bloquee al fondo`() = runTest {
        var now = 0L
        val session = mockSession(MutableStateFlow(unlockedState()))
        val settings = mockSettings(timeoutMinutes = 1, lockOnBackground = false)
        val controller = AutoLockController(session, settings, backgroundScope) { now }
        runCurrent()

        controller.onAppBackgrounded()
        now = 61_000L
        controller.onAppForegrounded()
        advanceTimeBy(1_000L)
        runCurrent()

        verify(session).lock()
    }

    @Test
    fun `una interaccion renueva el plazo de la sesion abierta`() = runTest {
        var now = 0L
        val session = mockSession(MutableStateFlow(unlockedState()))
        val settings = mockSettings(timeoutMinutes = 1, lockOnBackground = false)
        val controller = AutoLockController(session, settings, backgroundScope) { now }
        runCurrent()

        now = 50_000L
        controller.onUserInteraction()
        now = 61_000L
        advanceTimeBy(1_000L)
        runCurrent()

        verify(session, never()).lock()
    }

    private fun mockSession(state: MutableStateFlow<SessionState>): VaultSession =
        mock<VaultSession>().also { whenever(it.state).thenReturn(state) }

    private fun mockSettings(timeoutMinutes: Int, lockOnBackground: Boolean): SettingsDataStore =
        mock<SettingsDataStore>().also {
            whenever(it.lockTimeoutMinutes).thenReturn(MutableStateFlow(timeoutMinutes))
            whenever(it.lockOnBackground).thenReturn(MutableStateFlow(lockOnBackground))
        }

    private fun unlockedState(): SessionState = SessionState.Unlocked("FIXTURE-vault-id", 0L)
}
