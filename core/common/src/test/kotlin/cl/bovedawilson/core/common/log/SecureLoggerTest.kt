package cl.bovedawilson.core.common.log

import cl.bovedawilson.core.common.id.RandomId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cubre G-71. Cada prueba describe únicamente lo que demuestra: que el filtro por tipo
 * de build funciona y que los campos se serializan por su etiqueta redactada.
 *
 * Lo que **no** se demuestra aquí, porque no es demostrable en tiempo de ejecución, es
 * que ninguna llamada del proyecto interpole un secreto en `event`. Eso lo custodia la
 * prueba de higiene G-72 en `RepositoryHygieneTest`, que recorre el árbol de fuentes.
 */
class SecureLoggerTest {

    private val capturedLogs = mutableListOf<String>()

    @Before
    fun setup() {
        capturedLogs.clear()
        SecureLogger.setTestDelegate { level, tag, message, throwable ->
            val errorStr = throwable?.let { " - ${it.message}" } ?: ""
            capturedLogs.add("[$level] $tag: $message$errorStr")
        }
    }

    @After
    fun teardown() {
        SecureLogger.clearTestDelegate()
        // El singleton vuelve al valor que falla cerrado, para no filtrar el modo de
        // depuración a las pruebas que se ejecuten después (hallazgo B-4).
        SecureLogger.init(production = true)
    }

    @Test
    fun `G-71 en release se descartan v d i y se conservan w e`() {
        SecureLogger.init(production = true)
        SecureLogger.v("Tag", "evento_verbose")
        SecureLogger.d("Tag", "evento_debug")
        SecureLogger.i("Tag", "evento_info")

        assertTrue("No debe haber logs v/d/i en producción", capturedLogs.isEmpty())

        SecureLogger.w("Tag", "evento_advertencia")
        SecureLogger.e("Tag", "evento_error")

        assertEquals(2, capturedLogs.size)
        assertTrue(capturedLogs[0].contains("evento_advertencia"))
        assertTrue(capturedLogs[1].contains("evento_error"))
    }

    @Test
    fun `G-71 en debug se emiten v d i`() {
        SecureLogger.init(production = false)
        SecureLogger.v("Tag", "evento_verbose")
        SecureLogger.d("Tag", "evento_debug")
        SecureLogger.i("Tag", "evento_info")

        assertEquals(3, capturedLogs.size)
    }

    @Test
    fun `G-71 un campo Redact se emite por su etiqueta, no por su valor`() {
        SecureLogger.init(production = false)
        // Valor ficticio, no usar en producción. El constructor de RandomId es internal:
        // solo el código del propio módulo (aquí, la prueba) puede envolver un valor
        // arbitrario; fuera de :core:common la única vía es RandomId.generate().
        val idFicticio = RandomId("FIXTURE_0a1b2c3d-4e5f-6789-abcd-ef0123456789")

        SecureLogger.d("Tag", "item_guardado", "itemId" to Redact.idPrefix(idFicticio))

        assertEquals(1, capturedLogs.size)
        val salida = capturedLogs[0]
        assertTrue("Debe llevar el nombre del campo", salida.contains("itemId="))
        assertTrue("Debe llevar solo el prefijo", salida.contains("FIXTURE_…"))
        assertFalse("No debe llevar el identificador completo", salida.contains(idFicticio.value))
    }

    @Test
    fun `G-71 las fábricas de Redact no exponen contenido`() {
        SecureLogger.init(production = false)

        SecureLogger.d(
            "Tag",
            "bufer_procesado",
            "bytes" to Redact.size(32),
            "items" to Redact.count(3),
            "tipo" to Redact.type("una cadena cualquiera"),
            "abierta" to Redact.flag(true),
            "contenido" to Redact.redacted()
        )

        assertEquals(1, capturedLogs.size)
        val salida = capturedLogs[0]
        assertTrue(salida.contains("bytes=32B"))
        assertTrue(salida.contains("items=3"))
        assertTrue(salida.contains("tipo=String"))
        assertTrue(salida.contains("abierta=true"))
        assertTrue(salida.contains("contenido=[REDACTED]"))
        assertFalse(
            "type() emite la clase, nunca el valor",
            salida.contains("una cadena cualquiera")
        )
    }

    @Test
    fun `G-71 un evento sin campos se emite tal cual`() {
        SecureLogger.init(production = false)
        SecureLogger.i("Tag", "sesion_bloqueada")

        assertEquals(1, capturedLogs.size)
        assertTrue(capturedLogs[0].endsWith("sesion_bloqueada"))
    }
}
