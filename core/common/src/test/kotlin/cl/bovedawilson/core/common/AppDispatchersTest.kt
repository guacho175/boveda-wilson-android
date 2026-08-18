package cl.bovedawilson.core.common

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDispatchersTest {
    @Test
    fun `default values map to standard dispatchers`() {
        val dispatchers = AppDispatchers()
        assertEquals(Dispatchers.Main, dispatchers.main)
        assertEquals(Dispatchers.IO, dispatchers.io)
        assertEquals(Dispatchers.Default, dispatchers.default)
        assertEquals(Dispatchers.Unconfined, dispatchers.unconfined)
    }
}
