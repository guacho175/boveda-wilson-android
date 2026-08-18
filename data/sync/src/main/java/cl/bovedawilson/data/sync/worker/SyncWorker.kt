package cl.bovedawilson.data.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cl.bovedawilson.data.sync.engine.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Worker de sincronización con WorkManager.
 *
 * Ejecuta `SyncEngine.sync()` de forma periódica o bajo demanda:
 * - Puede mover ciphertext aunque la sesión esté bloqueada
 * - Clasifica errores: transitorios → reintento, permanentes → fallo
 * - Solo ejecuta con red conectada (Constraints)
 * - Backoff exponencial: 1 s → 15 minutos
 *
 * Implementa `docs/sync-protocol.md` §6.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: androidx.work.WorkerParameters,
    private val syncEngine: SyncEngine
) : CoroutineWorker(context, params) {

    // WorkManager recibe solo una categoría redactada; no se conserva una causa arbitraria.
    @Suppress("SwallowedException")
    override suspend fun doWork(): Result {
        return try {
            val result = syncEngine.sync()

            when {
                result.success -> Result.success()
                result.errors.isEmpty() -> Result.failure()
                result.errors.all { !it.isPermanent } -> Result.retry()
                else -> Result.failure()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "sync_work"
        private const val IMMEDIATE_WORK_NAME = "sync_work_immediate"
        private const val SYNC_TAG = "sync_work_all"
        private const val SYNC_INTERVAL_MINUTES = 15L
        private const val BACKOFF_INITIAL_DELAY_MS = 1000L

        /**
         * Programa sincronización periódica o bajo demanda.
         *
         * @param context contexto de la aplicación
         * @param backoff si true, usa backoff exponencial; si false, solo reintenta una vez
         */
        fun schedule(context: Context, backoff: Boolean = true) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                SYNC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(SYNC_TAG)
                .also {
                    if (backoff) {
                        it.setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            BACKOFF_INITIAL_DELAY_MS,
                            TimeUnit.MILLISECONDS
                        )
                    }
                }
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /** Cancela ambos tipos de sync y permite esperar el resultado vía [Operation]. */
        fun cancel(context: Context): Operation =
            WorkManager.getInstance(context).cancelAllWorkByTag(SYNC_TAG)

        /**
         * Ejecuta sincronización inmediata una sola vez.
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag(SYNC_TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
