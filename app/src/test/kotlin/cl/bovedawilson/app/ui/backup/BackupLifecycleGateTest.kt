package cl.bovedawilson.app.ui.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLifecycleGateTest {
    @Test
    fun `on stop invalida el resultado tardio de una operacion sensible`() {
        val gate = BackupLifecycleGate()
        val operation = gate.beginOperation()

        assertTrue(gate.isCurrent(operation))
        gate.invalidateOperations()

        assertFalse(gate.isCurrent(operation))
    }

    @Test
    fun `una operacion nueva no acepta el resultado de la anterior`() {
        val gate = BackupLifecycleGate()
        val previous = gate.beginOperation()
        val current = gate.beginOperation()

        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(current))
    }
}
