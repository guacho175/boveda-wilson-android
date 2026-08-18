package cl.bovedawilson.core.crypto.ciphertext

/**
 * Ciphertext opaco e inmutable. Sin constructor público: la única forma de obtener uno
 * nuevo es cifrar dentro de `:core:crypto` ([fromEncryption], interna). [fromPersisted] es
 * pública porque el pull/push con la sesión bloqueada necesita reconstruir el tipo desde
 * bytes ya guardados en Room o Firestore sin volver a cifrar; por eso mismo no es una
 * garantía absoluta del compilador de que esos bytes sean realmente ciphertext
 * (`docs/architecture.md` §3) — la disciplina de uso se refuerza con revisión y con la
 * prueba de arquitectura que confina esa fábrica a los mapeadores internos local/remoto.
 */
@JvmInline
value class Ciphertext private constructor(val bytes: ByteArray) {
    companion object {
        internal fun fromEncryption(bytes: ByteArray): Ciphertext = Ciphertext(bytes)
        fun fromPersisted(bytes: ByteArray): Ciphertext = Ciphertext(bytes)
    }
}
