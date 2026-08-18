package cl.bovedawilson.core.common.id

import java.security.SecureRandom

/**
 * Identificador aleatorio y no sensible (ítem, bóveda, revisión).
 *
 * El constructor es `internal`: fuera de `:core:common` solo se puede obtener un
 * `RandomId` a través de [generate], que deriva el valor de `SecureRandom`. Ningún
 * llamante externo puede envolver contenido de usuario en un `RandomId`, lo que permite
 * que `Redact.idPrefix` acepte este tipo en vez de un `String` arbitrario
 * (`SECURITY.md` §4).
 */
@JvmInline
value class RandomId internal constructor(internal val value: String) {

    companion object {
        private const val BYTE_LENGTH = 16
        private val secureRandom = SecureRandom()

        fun generate(): RandomId {
            val bytes = ByteArray(BYTE_LENGTH)
            secureRandom.nextBytes(bytes)
            return RandomId(bytes.joinToString(separator = "") { byte -> "%02x".format(byte) })
        }
    }
}
