package cl.bovedawilson.core.crypto.kdf

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mide el tiempo real de Argon2id con los parámetros de producción en el dispositivo
 * conectado (`CRYPTOGRAPHY.md` §14). No es parte del
 * gate automático: es una medición manual, cuyo resultado se transcribe a mano a
 * `CRYPTOGRAPHY.md` con fecha y dispositivo tras leer `INSTRUMENTATION_STATUS` en la salida
 * de `adb shell am instrument`. La aserción de esta prueba solo descarta un resultado
 * patológico (cuelgue o tiempo absurdo); el objetivo de diseño (0,5-2 s) se evalúa por
 * lectura humana del valor reportado, no aquí.
 */
@RunWith(AndroidJUnit4::class)
class Argon2idBenchmarkTest {

    @Test
    fun medirDerivacionConParametrosDeProduccion() {
        // Valor ficticio, solo para medir tiempo: no es una contraseña real.
        val passwordBytes = "benchmark-ficticio-no-es-una-contrasena-real".toByteArray(Charsets.UTF_8)
        val params = KdfPolicy.newProductionParameters()
        val kdf = Argon2idPasswordKdf()

        // Descarta el coste de la primera carga de clases antes de medir.
        kdf.derive(passwordBytes, params).fill(0)

        val start = System.nanoTime()
        val derived = kdf.derive(passwordBytes, params)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        derived.fill(0)

        val status = Bundle()
        status.putLong("argon2id_production_profile_ms", elapsedMs)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)

        assertTrue(
            "La derivación no debería tardar más de 30 s (posible cuelgue): ${elapsedMs}ms",
            elapsedMs < 30_000,
        )
    }
}
