package cl.bovedawilson.data.sync.worker

import android.content.Context
import androidx.lifecycle.Observer
import androidx.work.Operation
import cl.bovedawilson.core.common.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

fun interface SyncWorkCanceller {
    suspend fun cancelAndAwait(): Boolean
}

class AndroidSyncWorkCanceller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers
) : SyncWorkCanceller {
    override suspend fun cancelAndAwait(): Boolean = withContext(dispatchers.main) {
        withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) {
            SyncWorker.cancel(context).awaitSuccess()
        } ?: false
    }

    private companion object {
        const val CANCEL_TIMEOUT_MILLIS = 30_000L
    }
}

private suspend fun Operation.awaitSuccess(): Boolean = suspendCancellableCoroutine { continuation ->
    val operationState = state
    lateinit var observer: Observer<Operation.State>
    observer = Observer { current ->
        val completed = when (current) {
            is Operation.State.SUCCESS -> true
            is Operation.State.FAILURE -> false
            else -> null
        }
        if (completed != null && continuation.isActive) {
            operationState.removeObserver(observer)
            continuation.resume(completed)
        }
    }
    operationState.observeForever(observer)
    continuation.invokeOnCancellation { operationState.removeObserver(observer) }
}
