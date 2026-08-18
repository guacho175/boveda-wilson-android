package cl.bovedawilson.data.sync.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {

    @Test
    fun `operaciones remotas comparten una barrera exclusiva`() = runTest {
        val coordinator = SyncCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            coordinator.exclusive {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.exclusive { secondEntered = true }
        }
        runCurrent()
        assertFalse(secondEntered)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
    }
}
