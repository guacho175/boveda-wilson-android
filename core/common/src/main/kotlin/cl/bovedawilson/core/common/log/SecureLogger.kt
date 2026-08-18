package cl.bovedawilson.core.common.log

import android.util.Log
import cl.bovedawilson.core.common.id.RandomId

/**
 * Campo seguro para el registro.
 *
 * El constructor es **privado**: un `Redact` solo puede existir a través de las fábricas
 * de su acompañante. Ninguna fábrica acepta un `String` arbitrario como fuente de su
 * valor: `size`, `count`, `type`, `flag` y `redacted` no retienen nada del valor que
 * reciben, y `idPrefix` —la única que sí retiene un fragmento— solo acepta `RandomId`,
 * cuyo propio constructor impide envolver contenido de usuario. Esa es la garantía real:
 * no hay forma de construir un `Redact` que contenga una contraseña, una frase de
 * recuperación, entropía, una clave o el cuerpo de una nota.
 *
 * `label` es lo único que se imprime, y ya es seguro por construcción. No existe accesor
 * público al valor original porque el valor original nunca se guarda.
 *
 * Para material sensible en memoria no se usa este tipo: se usan `SecureBytes` y
 * `CharArray` borrables (`docs/architecture.md` §2), que introduce la Fase 2. Un secreto
 * no se «redacta para registrarlo»; sencillamente no se registra.
 */
@JvmInline
value class Redact private constructor(internal val label: String) {

    override fun toString(): String = label

    companion object {
        private const val ID_PREFIX_LENGTH = 8
        private const val REDACTED = "[REDACTED]"

        /**
         * Prefijo de un identificador aleatorio (ítem, bóveda o revisión). A diferencia
         * del resto de fábricas, sí retiene un fragmento del valor recibido; por eso solo
         * acepta [RandomId], cuyo único constructor genera bytes con `SecureRandom`. No
         * hay forma de pasarle contenido de usuario: el tipo lo impide en compilación.
         */
        fun idPrefix(id: RandomId): Redact {
            val prefix = id.value.take(ID_PREFIX_LENGTH)
            return if (prefix.isEmpty()) Redact(REDACTED) else Redact("$prefix…")
        }

        /** Tamaño de un buffer en bytes. Nunca su contenido. */
        fun size(bytes: Int): Redact = Redact("${bytes}B")

        /** Número de elementos de una colección. Nunca los elementos. */
        fun count(items: Int): Redact = Redact(items.toString())

        /** Nombre de la clase de un valor. Nunca el valor. */
        fun type(value: Any?): Redact =
            Redact(if (value == null) "null" else value.javaClass.simpleName)

        /** Bandera booleana no sensible (por ejemplo, «la sesión estaba abierta»). */
        fun flag(value: Boolean): Redact = Redact(value.toString())

        /**
         * Marcador explícito para un campo cuyo valor no puede aparecer en el registro.
         * Deja constancia de que el campo existe sin revelar nada de él.
         */
        fun redacted(): Redact = Redact(REDACTED)
    }
}

/**
 * Único punto de registro del proyecto. `SECURITY.md` §4 prohíbe
 * `android.util.Log` y `println` directos; la prueba de higiene G-67 lo verifica.
 *
 * La API **no acepta un `String` libre para contenido variable**: `event` describe qué
 * ocurrió y se escribe como literal constante, mientras que todo dato variable viaja
 * como `Pair<String, Redact>`, y `Redact` no puede construirse desde contenido sensible.
 * La prueba de higiene G-72 verifica que ninguna llamada interpole valores en `event`.
 *
 * En release se descarta todo lo que no sea advertencia o error operativo.
 */
object SecureLogger {
    private const val LEVEL_VERBOSE = 2
    private const val LEVEL_DEBUG = 3
    private const val LEVEL_INFO = 4
    private const val LEVEL_WARN = 5
    private const val LEVEL_ERROR = 6

    private var isProduction: Boolean = true

    private var testDelegate: ((Int, String, String, Throwable?) -> Unit)? = null

    internal fun setTestDelegate(
        delegate: (level: Int, tag: String, message: String, throwable: Throwable?) -> Unit
    ) {
        testDelegate = delegate
    }

    internal fun clearTestDelegate() {
        testDelegate = null
    }

    private fun format(event: String, fields: Array<out Pair<String, Redact>>): String =
        if (fields.isEmpty()) {
            event
        } else {
            fields.joinToString(separator = " ", prefix = "$event ") { (name, value) ->
                "$name=${value.label}"
            }
        }

    private fun delegateLog(level: Int, tag: String, message: String, throwable: Throwable?) {
        val delegate = testDelegate
        if (delegate != null) {
            delegate.invoke(level, tag, message, throwable)
        } else {
            when (level) {
                LEVEL_VERBOSE -> Log.v(tag, message, throwable)
                LEVEL_DEBUG -> Log.d(tag, message, throwable)
                LEVEL_INFO -> Log.i(tag, message, throwable)
                LEVEL_WARN -> Log.w(tag, message, throwable)
                LEVEL_ERROR -> Log.e(tag, message, throwable)
            }
        }
    }

    fun init(production: Boolean) {
        isProduction = production
    }

    fun v(tag: String, event: String, vararg fields: Pair<String, Redact>, throwable: Throwable? = null) {
        if (!isProduction) {
            delegateLog(LEVEL_VERBOSE, tag, format(event, fields), throwable)
        }
    }

    fun d(tag: String, event: String, vararg fields: Pair<String, Redact>, throwable: Throwable? = null) {
        if (!isProduction) {
            delegateLog(LEVEL_DEBUG, tag, format(event, fields), throwable)
        }
    }

    fun i(tag: String, event: String, vararg fields: Pair<String, Redact>, throwable: Throwable? = null) {
        if (!isProduction) {
            delegateLog(LEVEL_INFO, tag, format(event, fields), throwable)
        }
    }

    fun w(tag: String, event: String, vararg fields: Pair<String, Redact>, throwable: Throwable? = null) {
        delegateLog(LEVEL_WARN, tag, format(event, fields), throwable)
    }

    fun e(tag: String, event: String, vararg fields: Pair<String, Redact>, throwable: Throwable? = null) {
        delegateLog(LEVEL_ERROR, tag, format(event, fields), throwable)
    }
}
