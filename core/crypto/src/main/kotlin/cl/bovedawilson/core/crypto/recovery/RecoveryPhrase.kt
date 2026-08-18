package cl.bovedawilson.core.crypto.recovery

/**
 * Frase de recuperación de 24 palabras, para mostrarse **una sola vez** al crear la bóveda o
 * regenerar la recuperación (ADR-011). No es `data class` y su [toString] no revela contenido,
 * para que un registro o una comparación estructural accidental no impriman las palabras.
 */
class RecoveryPhrase internal constructor(private val words: List<String>) {
    val wordCount: Int get() = words.size

    /** Copia de las palabras, en orden. Quien la reciba es responsable de no persistirla. */
    fun toWordList(): List<String> = words.toList()

    override fun toString(): String = "RecoveryPhrase($wordCount words, redacted)"
}
