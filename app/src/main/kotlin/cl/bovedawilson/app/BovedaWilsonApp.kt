package cl.bovedawilson.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import cl.bovedawilson.core.common.log.SecureLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BovedaWilsonApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Único punto donde se decide el modo del registro, derivado del tipo de build y
        // no de una constante suelta (hallazgo M-1). `BuildConfig.DEBUG` es false en
        // release, de modo que v/d/i quedan descartados en el artefacto publicado.
        SecureLogger.init(production = !BuildConfig.DEBUG)

        // AppCheckInitializer se resuelve por build type (ADR-037): DebugProvider en
        // debug y Play Integrity en release. No inicializa Firebase sin configuración.
        AppCheckInitializer.installIfConfigured()
    }
}
