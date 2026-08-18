package cl.bovedawilson.data.sync.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {

    @Test
    fun `background invalida un unlock iniciado aunque vuelva a foreground`() {
        val session = VaultSession()
        val lease = session.beginUnlock()
        assertNotNull(lease)
        assertTrue(session.isUnlockLeaseValid(requireNotNull(lease)))

        session.onAppBackgrounded()
        session.onAppForegrounded()

        assertFalse(session.isUnlockLeaseValid(lease))
    }

    @Test
    fun `lock invalida todos los leases anteriores`() {
        val session = VaultSession()
        val lease = requireNotNull(session.beginUnlock())

        session.lock()

        assertFalse(session.isUnlockLeaseValid(lease))
    }
}
