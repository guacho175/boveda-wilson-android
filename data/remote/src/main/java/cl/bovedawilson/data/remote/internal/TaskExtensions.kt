package cl.bovedawilson.data.remote.internal

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Puente mínimo `Task<T> → suspend fun` sin añadir `kotlinx-coroutines-play-services`
 * (`docs/DEPENDENCY_POLICY.md`, Fase 4). No se cancela la `Task` subyacente: la API de
 * Play Services no ofrece un modo de cancelación cooperativa para las operaciones de
 * Auth/Firestore usadas aquí.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { exception -> continuation.resumeWithException(exception) }
}
