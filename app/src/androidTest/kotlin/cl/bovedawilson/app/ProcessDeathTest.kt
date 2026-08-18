package cl.bovedawilson.app

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.bovedawilson.data.sync.session.SessionState
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import android.os.ParcelFileDescriptor

/**
 * G-38: verifica el proceso nuevo después de que `tools/verify-process-death.ps1` haya confirmado
 * desde fuera que el PID anterior murió. La instrumentación no puede ejecutar `am force-stop`
 * contra su propio paquete: se terminaría antes de reportar el resultado.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    @Test
    fun externallyKilledProcessStartsLockedWithoutRestoredSensitiveState() {
        assumeTrue(
            "Requires tools/verify-process-death.ps1 host orchestration",
            InstrumentationRegistry.getArguments().getString(EXTERNAL_DEATH_ARGUMENT) == "true",
        )
        val previousPid = requireNotNull(
            InstrumentationRegistry.getArguments().getString(PREVIOUS_PID_ARGUMENT),
        ).toInt()
        assertNotEquals(previousPid, Process.myPid())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = EntryPointAccessors.fromApplication(
            instrumentation.targetContext.applicationContext,
            VaultSessionEntryPoint::class.java,
        ).vaultSession()

        assertEquals(SessionState.Locked, session.state.value)

        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${instrumentation.targetContext.packageName}/.MainActivity " +
                    "--ez ${MainActivity.PROCESS_DEATH_VERIFY_EXTRA} true",
            ),
        ).bufferedReader().use { it.readText() }
        org.junit.Assert.assertTrue(launchOutput.contains("Status: ok"))
        onView(withHint("Contraseña maestra")).check(matches(isDisplayed()))
        onView(withText(MainActivity.PROCESS_DEATH_CANARY)).check(doesNotExist())
    }

    private companion object {
        private const val EXTERNAL_DEATH_ARGUMENT = "processDeathVerified"
        private const val PREVIOUS_PID_ARGUMENT = "previousPid"
    }
}
