package cl.bovedawilson.app.ui.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupUiStateTest {
    @Test
    fun `string representation redacts recovery phrase`() {
        val canary = "FIXTURE-RECOVERY-WORD-NOT-REAL"
        val rendered = BackupUiState(recoveryPhrase = listOf(canary)).toString()

        assertFalse(rendered.contains(canary))
        assertTrue(rendered.contains("REDACTED"))
    }
}
